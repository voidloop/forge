package forge.rl;

import forge.ai.AiCostDecision;
import forge.game.GameActionUtil;
import forge.game.GameEntity;
import forge.game.ability.ApiType;
import forge.game.ability.effects.CharmEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardLists;
import forge.game.cost.CostDiscard;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostPayLife;
import forge.game.cost.CostSacrifice;
import forge.game.cost.CostPayment;
import forge.game.cost.ICostVisitor;
import forge.game.cost.PaymentDecision;
import forge.game.player.Player;
import forge.game.keyword.Keyword;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.OptionalCost;
import forge.game.spellability.OptionalCostValue;
import forge.game.spellability.TargetRestrictions;
import forge.game.spellability.TargetChoices;
import forge.game.ability.AbilityUtils;
import forge.game.staticability.StaticAbilityMode;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** A paused action proposal; querying and selecting never pay or advance play. */
public final class ActionDraft {
    public enum Stage { Cost, X, Discard, Mode, Target, Complete }

    public static final class OverflowException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        OverflowException(String message) { super(message); }
    }

    private static final int MAX_OPTIONS = 257;

    private final Player payer;
    private final Card source;
    private final long sourceTimestamp;
    private final ZoneType sourceZone;
    private final SpellAbility root;
    private final List<SpellAbility> costs = new ArrayList<>();
    private final CardCollection discarded = new CardCollection();
    private final Map<Card, Long> selectedTimestamps = new HashMap<>();
    private final List<AbilitySub> modes = new ArrayList<>();
    private SpellAbility action;
    private SpellAbility effect;
    private CostDiscard discardCost;
    private Stage stage = Stage.Cost;
    private boolean finished;
    private int probes;

    public ActionDraft(SpellAbility original, Player payer) {
        this.payer = payer;
        source = original.getHostCard();
        sourceTimestamp = source.getGameTimestamp();
        sourceZone = source.getZone().getZoneType();
        root = original.copy(payer);
        validateSupported(root);
        detachTargets(root);
        for (SpellAbility option : GameActionUtil.getAdditionalCostSpell(root)) {
            List<OptionalCostValue> optional = GameActionUtil.getOptionalCostValues(option);
            List<SpellAbility> variants = new ArrayList<>(List.of(option));
            if (!optional.isEmpty()) {
                variants.add(GameActionUtil.addOptionalCosts(option, optional));
            }
            for (SpellAbility variant : variants) {
                // Costs have their own proposal fields; preserve the action's catalog effect text.
                variant.setDescription(root.getDescription());
                validateSupported(variant);
                detachTargets(variant);
                if (inCastContext(variant, () -> variant.costHasManaX()
                        ? !xOptions(variant).isEmpty() : canPay(variant) && hasEnoughTargets(variant))) {
                    costs.add(variant);
                }
            }
        }
    }

    public Stage getStage() {
        checkCurrent();
        return stage;
    }

    /** An unselected root; conditional cost and target choices remain on the draft. */
    public SpellAbility getRootAction() {
        checkCurrent();
        return root;
    }

    /** Cost options are detached abilities; subsequent options are game objects. */
    public List<?> getOptions() {
        checkCurrent();
        return switch (stage) {
            case Cost -> List.copyOf(costs);
            case X -> List.copyOf(inCastContext(action, () -> xOptions(action)));
            case Discard -> List.copyOf(discardOptions());
            case Mode -> List.copyOf(inCastContext(action, () -> modeOptions(action)));
            case Target -> List.copyOf(inCastContext(action, () -> targetOptions(effect)));
            case Complete -> List.of();
        };
    }

    public SpellAbility getAction() {
        checkCurrent();
        return action;
    }

    /** The selected executable part; a modal header is not a selected effect. */
    public SpellAbility getEffectAction() {
        checkCurrent();
        return effect;
    }

    public void choose(int index) {
        List<?> options = getOptions();
        if (index < 0 || index >= options.size()) {
            throw new IllegalArgumentException("Choice is not in the current options");
        }
        switch (stage) {
            case Cost -> {
                action = (SpellAbility) options.get(index);
                effect = action.getApi() == ApiType.Charm ? null : action;
                for (CostPart part : action.getPayCosts().getCostParts()) {
                    if (part instanceof CostDiscard discard) {
                        discardCost = discard;
                    }
                }
                stage = action.costHasManaX() ? Stage.X : paymentStage();
            }
            case X -> {
                action.setXManaCostPaid((Integer) options.get(index));
                stage = paymentStage();
            }
            case Discard -> {
                Card card = (Card) options.get(index);
                discarded.add(card);
                selectedTimestamps.put(card, card.getGameTimestamp());
                if (discarded.size() == discardCost.getAbilityAmount(action)) {
                    List<CostPart> parts = action.getPayCosts().getCostParts();
                    parts.set(parts.indexOf(discardCost), new SelectedDiscardCost(discardCost, discarded));
                    stage = effectStage();
                }
            }
            case Mode -> {
                modes.add((AbilitySub) options.get(index));
                if (modes.size() == getMaximum()) {
                    finishModes();
                }
            }
            case Target -> {
                GameEntity target = (GameEntity) options.get(index);
                effect.getTargets().add(target);
                if (target instanceof Card card) {
                    selectedTimestamps.put(card, card.getGameTimestamp());
                }
                if (effect.isMaxTargetChosen()) {
                    advanceEffect();
                }
            }
            case Complete -> throw new IllegalStateException("Action is already complete");
        }
    }

    /** Optional target completion is separate from passing priority. */
    public boolean canFinish() {
        checkCurrent();
        return stage == Stage.Complete
                || stage == Stage.Mode && modes.size() >= getMinimum()
                || stage == Stage.Target && effect.isTargetNumberValid()
                    && inCastContext(action, () -> hasEnoughTargets(effect.getSubAbility()));
    }

    /** Complete this group, never pass priority or skip a mandatory later group. */
    public void finishChoice() {
        if (!canFinish() || stage == Stage.Complete) {
            throw new IllegalStateException("The current choice cannot be completed");
        }
        if (stage == Stage.Mode) {
            finishModes();
        } else {
            advanceEffect();
        }
    }

    public int getMinimum() {
        return stage == Stage.Mode ? modeCount(action, "MinCharmNum", "CharmNum")
                : stage == Stage.Target ? effect.getMinTargets() : 1;
    }

    public int getMaximum() {
        return stage == Stage.Mode ? modeCount(action, "CharmNum", "CharmNum")
                : stage == Stage.Target ? effect.getMaxTargets() : 1;
    }

    public boolean allowsRepeat() {
        return stage == Stage.Mode && action.hasParam("CanRepeatModes");
    }

    public List<AbilitySub> getSelectedModes() { return List.copyOf(modes); }

    private static int modeCount(SpellAbility ability, String param, String fallback) {
        return AbilityUtils.calculateAmount(ability.getHostCard(),
                ability.getParamOrDefault(param, ability.getParamOrDefault(fallback, "1")), ability);
    }

    private void finishModes() {
        action.setChosenList(new ArrayList<>(modes));
        action.setSubAbility(null);
        CharmEffect.chainAbilities(action, action.getChosenList());
        detachTargets(action.getSubAbility());
        effect = action.getSubAbility();
        stage = targetStage();
    }

    private void advanceEffect() {
        effect = effect.getSubAbility();
        stage = targetStage();
    }

    public SpellAbility finish() {
        checkCurrent();
        if (!canFinish() || !inCastContext(action, () -> canPay(action)
                && source.getGame().getStack().hasLegalTargeting(action) && targetsStillLegal())) {
            throw new IllegalStateException("Action has no legal complete selection");
        }
        if (discardCost != null && !discardCandidates(action, discardCost).containsAll(discarded)) {
            throw new IllegalStateException("Selected discard payment is no longer available");
        }
        for (Map.Entry<Card, Long> selected : selectedTimestamps.entrySet()) {
            Card current = source.getGame().getCardState(selected.getKey(), null);
            if (current == null || current.getGameTimestamp() != selected.getValue()) {
                throw new IllegalStateException("Selected object is no longer current");
            }
        }
        finished = true;
        return action;
    }

    private Stage targetStage() {
        while (effect != null && (!effect.usesTargeting() || effect.getMaxTargets() == 0)) {
            effect = effect.getSubAbility();
        }
        return effect == null ? Stage.Complete : Stage.Target;
    }

    private Stage effectStage() {
        return effect == null ? Stage.Mode : targetStage();
    }

    private Stage paymentStage() {
        return discardCost == null ? effectStage() : Stage.Discard;
    }

    private List<Integer> xOptions(SpellAbility ability) {
        // shortcut: X-dependent cost modifiers stay delegated; extend before supporting non-monotone costs.
        if (!ability.getSVar("X").equals("Count$xPaid") || ability.hasParam("XAlternative")
                || ability.hasParam("RaiseCost") || ability.hasParam("ReduceCost")) {
            throw new UnsupportedOperationException("This X needs another announcement slice");
        }
        CardCollection costSources = new CardCollection(
                source.getGame().getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES));
        costSources.add(source);
        for (Card card : costSources) {
            if (card.getStaticAbilities().stream().anyMatch(staticAbility ->
                    staticAbility.checkMode(StaticAbilityMode.RaiseCost)
                    || staticAbility.checkMode(StaticAbilityMode.ReduceCost)
                    || staticAbility.checkMode(StaticAbilityMode.SetCost))) {
                throw new UnsupportedOperationException("X with cost modifiers needs another mana slice");
            }
        }
        List<Integer> options = new ArrayList<>();
        Integer previous = ability.getXManaCostPaid();
        try {
            int minimum = ability.getPayCosts().getCostMana().getXMin();
            for (int x = minimum; ; x++) {
                ability.setXManaCostPaid(x);
                if (!ActionMana.canPay(ability, payer)) {
                    break;
                }
                if (x - minimum >= MAX_OPTIONS) {
                    throw new OverflowException("X proposal exceeds its feasibility-probe budget");
                }
                if (canPayNonMana(ability) && hasEnoughTargets(ability)) {
                    options.add(x);
                }
            }
            return options;
        } finally {
            ability.setXManaCostPaid(previous);
        }
    }

    private boolean targetsStillLegal() {
        SpellAbility validation = action.copy(payer);
        detachTargets(validation);
        SpellAbility originalPart = action;
        for (SpellAbility part = validation; part != null; part = part.getSubAbility()) {
            if (part.usesTargeting()) {
                for (var target : originalPart.getTargets()) {
                    if (!part.canTarget(target) || part.getTargets().contains(target)) {
                        return false;
                    }
                    part.getTargets().add(target);
                }
                if (!part.isTargetNumberValid()) { return false; }
            }
            originalPart = originalPart.getSubAbility();
        }
        return true;
    }

    private void checkCurrent() {
        if (finished || source.getGame().isGameOver()
                || source.getGameTimestamp() != sourceTimestamp
                || !source.isInZone(sourceZone)) {
            throw new IllegalStateException("Action proposal is no longer current");
        }
    }

    private boolean canPay(SpellAbility ability) {
        return canPayNonMana(ability) && ActionMana.canPay(ability, payer);
    }

    private boolean canPayNonMana(SpellAbility ability) {
        if (!ability.canPlay()
                || !CostPayment.canPayAdditionalCosts(ability.getPayCosts(), ability, false, payer)) {
            return false;
        }
        for (CostPart part : ability.getPayCosts().getCostParts()) {
            if (part instanceof CostDiscard discard
                    && discardCandidates(ability, discard).size() < discard.getAbilityAmount(ability)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasEnoughTargets(SpellAbility ability) {
        probes = 0;
        return feasibleTargets(ability);
    }

    private boolean feasibleTargets(SpellAbility ability) {
        if (++probes > 4096) {
            throw new OverflowException("Target feasibility exceeds its native probe budget");
        }
        if (ability == null) { return true; }
        if (ability.getApi() == ApiType.Charm) {
            List<AbilitySub> options = modeOptions(ability);
            int minimum = modeCount(ability, "MinCharmNum", "CharmNum");
            return minimum == 0 || (ability.hasParam("CanRepeatModes")
                    ? !options.isEmpty() : options.size() >= minimum);
        }
        if (!ability.usesTargeting()) { return feasibleTargets(ability.getSubAbility()); }
        if (ability.isTargetNumberValid() && feasibleTargets(ability.getSubAbility())) { return true; }
        if (ability.isMaxTargetChosen()) { return false; }
        // Native canTarget supplies all dependencies; only detached target lists are changed.
        TargetChoices previous = ability.getTargets().clone();
        for (GameEntity target : rawTargets(ability)) {
            ability.getTargets().add(target);
            try {
                if (feasibleTargets(ability)) { return true; }
            } finally {
                ability.setTargets(previous.clone());
            }
        }
        return false;
    }

    private List<AbilitySub> modeOptions(SpellAbility ability) {
        List<AbilitySub> possible = CharmEffect.makePossibleOptions(ability);
        List<AbilitySub> options = new ArrayList<>();
        for (AbilitySub mode : possible) {
            if (!ability.hasParam("CanRepeatModes") && modes.contains(mode)) { continue; }
            List<AbilitySub> prefix = new ArrayList<>(modes);
            prefix.add(mode);
            if (modeCompletion(ability, possible, prefix, new int[] {0})) { options.add(mode); }
        }
        return options;
    }

    private boolean modeCompletion(SpellAbility ability, List<AbilitySub> possible,
            List<AbilitySub> prefix, int[] budget) {
        if (++budget[0] > 4096) {
            throw new OverflowException("Mode feasibility exceeds its native probe budget");
        }
        if (prefix.size() >= modeCount(ability, "MinCharmNum", "CharmNum")) {
            SpellAbility candidate = ability.copy(payer);
            candidate.setSubAbility(null);
            CharmEffect.chainAbilities(candidate, new ArrayList<>(prefix));
            detachTargets(candidate.getSubAbility());
            return hasEnoughTargets(candidate.getSubAbility());
        }
        for (AbilitySub mode : possible) {
            if (!ability.hasParam("CanRepeatModes") && prefix.contains(mode)) { continue; }
            prefix.add(mode);
            try {
                if (modeCompletion(ability, possible, prefix, budget)) { return true; }
            } finally {
                prefix.remove(prefix.size() - 1);
            }
        }
        return false;
    }

    private List<GameEntity> targetOptions(SpellAbility ability) {
        List<GameEntity> options = new ArrayList<>();
        for (GameEntity target : rawTargets(ability)) {
            TargetChoices previous = ability.getTargets().clone();
            ability.getTargets().add(target);
            try {
                if (hasEnoughTargets(ability)) { options.add(target); }
            } finally {
                ability.setTargets(previous);
            }
        }
        return options;
    }

    private List<GameEntity> rawTargets(SpellAbility ability) {
        return ability.getTargetRestrictions().getAllCandidates(ability).stream()
                .filter(target -> !ability.getTargets().contains(target)).toList();
    }

    private CardCollection discardOptions() {
        CardCollection options = discardCandidates(action, discardCost);
        options.removeAll(discarded);
        return options;
    }

    private static CardCollection discardCandidates(SpellAbility ability, CostDiscard cost) {
        Player payer = ability.getActivatingPlayer();
        CardCollection options = payer.canDiscardBy(ability, false)
                ? CardLists.getValidCards(payer.getCardsIn(ZoneType.Hand), cost.getType().split(";"),
                        payer, ability.getHostCard(), ability)
                : new CardCollection();
        options.remove(ability.getHostCard());
        return CardLists.filter(options, card -> card.canBeDiscardedBy(ability, false));
    }

    private <T> T inCastContext(SpellAbility ability, Supplier<T> query) {
        SpellAbility previous = source.getCastSA();
        if (ability.isSpell()) {
            source.setCastSA(ability);
        }
        try {
            return query.get();
        } finally {
            if (ability.isSpell()) {
                source.setCastSA(previous);
            }
        }
    }

    private static void detachTargets(SpellAbility ability) {
        if (ability == null) { return; }
        if (ability.usesTargeting()) {
            // Forge's ability copy shares target restrictions with the live source.
            ability.setTargetRestrictions(new TargetRestrictions(ability.getTargetRestrictions()));
            ability.resetTargets();
        }
        if (ability.getSubAbility() != null) {
            detachTargets(ability.getSubAbility());
        }
        if (ability.getApi() == ApiType.Charm) {
            for (AbilitySub mode : ability.getAdditionalAbilityList("Choices")) {
                detachTargets(mode);
            }
        }
    }

    private static void validateSupported(SpellAbility ability) {
        if (ability.costHasX() && !ability.costHasManaX()
                || ability.isManaAbility() || ability.isCastFaceDown()) {
            throw new UnsupportedOperationException("This action needs another explicit-choice slice");
        }
        if (ability.isSpell()) {
            if (ability.getAlternateHost(ability.getHostCard()) != null) {
                throw new UnsupportedOperationException("Alternate-face costs need another choice slice");
            }
            List<OptionalCostValue> optional = GameActionUtil.getOptionalCostValues(ability);
            if (optional.size() > 1) {
                throw new UnsupportedOperationException("Multiple optional costs need another choice slice");
            }
            for (OptionalCostValue value : optional) {
                if (value.getType() == OptionalCost.Offering || value.getType() == OptionalCost.Entwine
                        || !value.getType().getPip().isEmpty()) {
                    throw new UnsupportedOperationException("This optional cost needs another choice slice");
                }
                validatePayments(value.getCost().getCostParts());
            }
            for (Keyword keyword : List.of(Keyword.CASUALTY, Keyword.CONSPIRE, Keyword.MULTIKICKER,
                    Keyword.OFFSPRING, Keyword.REPLICATE, Keyword.SQUAD, Keyword.HARMONIZE)) {
                if (ability.getHostCard().hasKeyword(keyword)) {
                    throw new UnsupportedOperationException("Repeated keyword costs need another choice slice");
                }
            }
        }
        validatePayments(ability.getPayCosts().getCostParts());
        validateTargeting(ability);
        for (SpellAbility part = ability.getSubAbility(); part != null; part = part.getSubAbility()) {
            validateTargeting(part);
            if (part.getApi() == ApiType.Charm) {
                throw new UnsupportedOperationException("Nested modes need another choice slice");
            }
        }
        if (ability.getApi() == ApiType.Charm) {
            if (!ability.getParamOrDefault("CharmNum", "1").matches("[0-9]+")
                    || !ability.getParamOrDefault("MinCharmNum", "1").matches("[0-9]+")
                    || ability.hasParam("Random") || ability.hasParam("Optional")
                    || ability.hasParam("Chooser") || ability.hasParam("Pawprint")) {
                throw new UnsupportedOperationException("This modal announcement needs another choice slice");
            }
            for (AbilitySub mode : ability.getAdditionalAbilityList("Choices")) {
                validateTargeting(mode);
                if (mode.hasParam("ModeCost") || mode.hasParam("PrecostDesc")) {
                    throw new UnsupportedOperationException("Paid modes need another payment slice");
                }
                for (SpellAbility part = mode.getSubAbility(); part != null; part = part.getSubAbility()) {
                    validateTargeting(part);
                    if (part.getApi() == ApiType.Charm) {
                        throw new UnsupportedOperationException("This modal chain needs another choice slice");
                    }
                }
            }
            if (modeCount(ability, "CharmNum", "CharmNum") > 63) {
                throw new OverflowException("Mode sequence exceeds the choice-step budget");
            }
        }
    }

    private static void validatePayments(List<CostPart> parts) {
        int discards = 0;
        for (CostPart part : parts) {
            if (part instanceof CostDiscard discard) {
                discards++;
                String type = discard.getType();
                if (discard.convertAmount() == null || discard.convertAmount() <= 0
                        || discard.payCostFromSource() || type.equals("Random")
                        || type.equals("Hand") || type.equals("LastDrawn") || type.contains("+")) {
                    throw new UnsupportedOperationException("This discard needs another payment slice");
                }
            } else if (part instanceof CostPayLife && part.convertAmount() == null) {
                throw new UnsupportedOperationException("Variable life payment needs another cost slice");
            } else if (part instanceof CostSacrifice sacrifice) {
                if (!sacrifice.payCostFromSource() || !Integer.valueOf(1).equals(sacrifice.convertAmount())) {
                    throw new UnsupportedOperationException("This sacrifice needs another payment slice");
                }
            } else if (!(part instanceof CostPartMana) && !(part instanceof CostPayLife)) {
                throw new UnsupportedOperationException("This payment needs another explicit-choice slice");
            }
        }
        if (discards > 1) {
            throw new UnsupportedOperationException("Multiple discard costs are not yet supported");
        }
    }

    private static void validateTargeting(SpellAbility ability) {
        if (ability.usesTargeting()) {
            TargetRestrictions target = ability.getTargetRestrictions();
            if (ability.hasParam("TargetingPlayer") || ability.isDividedAsYouChoose()
                    || target.getZone().contains(ZoneType.Stack) || target.isRandomTarget()
                    || target.isRandomNumTargets() || target.isForEachPlayer()
                    || target.isDifferentControllers() || target.isDifferentCMC()
                    || target.isDifferentNames() || target.isEqualToughness()
                    || target.isSameController() || target.isWithoutSameCreatureType()
                    || target.isWithSameCreatureType() || target.isWithSameCardType()
                    || ability.hasParam("TargetsWithSharedCardType")
                    || ability.hasParam("TargetsWithSharedTypes")
                    || ability.hasParam("MaxTotalTargetCMC") || ability.hasParam("MaxTotalTargetPower")) {
                throw new UnsupportedOperationException("Dependent targets need another targeting slice");
            }
        }
    }

    /** Preserve payment objects through Forge's hardcoded AI cost visitor. */
    private static final class SelectedDiscardCost extends CostDiscard {
        private static final long serialVersionUID = 1L;
        private final CardCollection selected;

        SelectedDiscardCost(CostDiscard original, CardCollection selected) {
            super(original.getAmount(), original.getType(), original.getTypeDescription());
            this.selected = new CardCollection(selected);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T accept(ICostVisitor<T> visitor) {
            if (visitor instanceof AiCostDecision) {
                // AiCostDecision's visitor result is always PaymentDecision.
                return (T) PaymentDecision.card(selected);
            }
            return visitor.visit(this);
        }
    }
}
