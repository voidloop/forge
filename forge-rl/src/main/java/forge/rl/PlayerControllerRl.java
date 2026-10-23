package forge.rl;

import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimap;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.card.ICardFace;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.*;
import forge.game.ability.effects.RollDiceEffect;
import forge.game.card.*;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.event.GameEventCombatChanged;
import forge.game.cost.*;
import forge.game.keyword.KeywordInterface;
import forge.game.keyword.Keyword;
import forge.game.mana.Mana;
import forge.game.mana.ManaConversionMatrix;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.*;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.*;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.PlayerZone;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.util.ITriggerEvent;
import forge.util.collect.FCollectionView;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import forge.util.MessageUtil;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;


/** Independent agent controller using native execution and blocking decisions. */
public class PlayerControllerRl extends PlayerController {
    private final IPlayerDecisionCallback callback;
    private volatile RuntimeException failure;
    public RuntimeException getFailure() {
        return failure;
    }
    private final Player observer;
    private final ObservationFacade observation;
    private final java.util.Deque<SpellAbility> activeActions = new java.util.ArrayDeque<>();
    private int rawCandidateCount;
    private int chosenActionCount;
    private int chosenPassActionCount;
    private int failedActionCount;
    private int cardsBottomed;
    private int requests;
    private final Map<Player, CardCollection> temporaryViews = new java.util.LinkedHashMap<>();


    public PlayerControllerRl(Game game, Player player, LobbyPlayerRl lobby, IPlayerDecisionCallback callback) {
        this(game, player, lobby, callback, player);
    }

    public PlayerControllerRl(Game game, Player player, LobbyPlayerRl lobby,
            IPlayerDecisionCallback callback, Player observer) {
        super(game, player, lobby);
        this.callback = callback;
        this.observer = observer;
        this.observation = player != observer && observer.getController() instanceof PlayerControllerRl controller
                ? controller.getObservation() : new ObservationFacade(observer);
        getFullControl().add(FullControlFlag.ChooseCostOrder);
        getFullControl().add(FullControlFlag.ChooseCostReductionOrderAndVariableAmount);
        getFullControl().add(FullControlFlag.ChooseManaPoolShard);
        getFullControl().add(FullControlFlag.NoPaymentFromManaAbility);
    }

    public ObservationFacade getObservation() { return observation; }

    private List<Integer> answer(NativeDecision request) {
        try {
            if (failure != null) { throw failure; }
            if (player != observer && player.getControllingPlayer() != observer) {
                throw new IllegalStateException("Decision routed to an unauthorized observer");
            }
            requests++;
            callback.observe(observation);
            List<Integer> result = callback.choose(observation.decision(request));
            request.validateAnswer(result);
            return result;
        } catch (RuntimeException error) {
            if (failure == null) { failure = error; }
            throw error;
        }
    }

    <T> List<T> select(String kind, SpellAbility source, String prompt,
            List<T> options, int minimum, int maximum, boolean allowEmpty,
            boolean repeat, boolean ordered, java.util.function.Predicate<List<Integer>> validator) {
        return select(kind, source, prompt, options, minimum, maximum, allowEmpty,
                repeat, ordered, validator, Map.of());
    }

    <T> List<T> select(String kind, SpellAbility source, String prompt,
            List<T> options, int minimum, int maximum, boolean allowEmpty,
            boolean repeat, boolean ordered, java.util.function.Predicate<List<Integer>> validator,
            Map<String, Object> context) {
        if (options.isEmpty() && minimum == 0) { return List.of(); }
        NativeDecision request = new NativeDecision(kind, player, source, prompt,
                options, minimum, repeat ? maximum : Math.min(maximum, options.size()),
                allowEmpty, repeat, ordered, false, validator, context == null ? Map.of() : context);
        List<T> result = new java.util.ArrayList<>();
        for (int index : answer(request)) { result.add(options.get(index)); }
        return result;
    }

    <T> T one(String kind, SpellAbility source, String prompt, List<T> options) {
        return select(kind, source, prompt, options, 1, 1, false, false, false, null).get(0);
    }

    Integer number(SpellAbility source, String prompt, int minimum, int maximum, boolean optional) {
        List<Integer> value = answer(new NativeDecision("number", player, source, prompt,
                List.of(), minimum, maximum, optional, false, false, true, null));
        return value.isEmpty() ? null : value.get(0);
    }

    <T> T optional(String kind, SpellAbility source, String prompt, List<T> options) {
        List<T> chosen = select(kind, source, prompt, options, 0, 1, true, false, false, null);
        return chosen.isEmpty() ? null : chosen.get(0);
    }

    private IllegalArgumentException invalidRequest(String detail) {
        var error = new IllegalArgumentException("Invalid native request: " + detail);
        if (failure == null) { failure = error; }
        return error;
    }

    @Override
    public Player chooseStartingPlayer(boolean firstGame) {
        return one("starting_player", null, "Choose the starting player", new java.util.ArrayList<>(getGame().getPlayers()));
    }

    @Override
    public PlayerZone chooseStartingHand(List<PlayerZone> zones) {
        if (zones.size() == 1) { return zones.get(0); }
        return one("starting_hand", null, "Choose a starting hand", zones);
    }

    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        return one("mulligan", null, "Keep this hand; cards to return: " + cardsToReturn, List.of(true, false));
    }

    @Override
    public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int count) {
        List<Card> chosen = select("mulligan_bottom", null, "Choose cards to put on the bottom, in order",
                new java.util.ArrayList<>(hand), count, count, false, false, true, null);
        cardsBottomed += chosen.size();
        return new CardCollection(chosen);
    }

    @Override
    public List<SpellAbility> chooseSaToActivateFromOpeningHand(List<SpellAbility> abilities) {
        return select("opening_actions", null, "Choose opening-hand actions", abilities,
                0, abilities.size(), false, false, true, null);
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        List<Object> options = new java.util.ArrayList<>();
        options.add("pass");
        Map<SpellAbility, Player> actors = rememberGameActors();
        try {
            CardCollection sources = new CardCollection(getGame().getCardsIn(ZoneType.Battlefield));
            sources.addAll(player.getCardsIn(ZoneType.Hand));
            // External permissions belong to native zone filters, including opponents' cards.
            sources.addAll(player.getCardsActivatableInExternalZones(true));
            for (Card card : sources) {
                for (SpellAbility ability : card.getAllPossibleAbilities(player, true)) {
                    // Native identity also binds activation limits and mana reentry.
                    options.add(ability);
                }
            }
        } finally {
            // Native enumeration binds actors on originals, including abilities already on the stack.
            actors.forEach(SpellAbility::setActivatingPlayer);
        }
        rawCandidateCount += options.size() - 1;
        Object chosen = one("priority", null, "Choose an action or pass priority", options);
        if (chosen instanceof SpellAbility ability) {
            ability.setActivatingPlayer(player);
            return List.of(ability);
        }
        chosenPassActionCount++;
        return null;
    }

    private Map<SpellAbility, Player> rememberGameActors() {
        Map<SpellAbility, Player> actors = new java.util.LinkedHashMap<>();
        for (Card card : getGame().getCardsInGame()) {
            for (var state : card.getStates()) {
                for (SpellAbility ability : card.getState(state).getSpellAbilities()) {
                    rememberActors(ability, actors);
                }
            }
        }
        return actors;
    }

    private static void rememberActors(SpellAbility ability, Map<SpellAbility, Player> actors) {
        if (actors.containsKey(ability)) { return; }
        actors.put(ability, ability.getActivatingPlayer());
        if (ability.getSubAbility() != null) { rememberActors(ability.getSubAbility(), actors); }
        for (SpellAbility extra : ability.getAdditionalAbilities().values()) { rememberActors(extra, actors); }
        for (List<AbilitySub> group : ability.getAdditionalAbilityLists().values()) {
            for (AbilitySub extra : group) { rememberActors(extra, actors); }
        }
    }

    @Override
    public boolean playChosenSpellAbility(SpellAbility ability) {
        chosenActionCount++;
        activeActions.push(ability);
        try {
            boolean result = PlaySpellAbility.playSpellAbility(this, player, ability);
            if (!result) { failedActionCount++; }
            return result;
        } finally { activeActions.pop(); }
    }

    @Override
    public SpellAbility getAbilityToPlay(Card host, List<SpellAbility> abilities, ITriggerEvent event) {
        if (abilities.isEmpty()) { return null; }
        if (abilities.size() == 1) { return abilities.get(0); }
        return one("additional_cost", activeActions.peek(), "Choose an additional-cost alternative", abilities);
    }

    @Override
    public List<OptionalCostValue> chooseOptionalCosts(SpellAbility source, List<OptionalCostValue> costs) {
        return select("optional_costs", source, "Choose optional costs", costs, 0, costs.size(), false, false, false, null);
    }

    @Override
    public List<AbilitySub> chooseModeForAbility(SpellAbility source, List<AbilitySub> modes, int min, int max, boolean repeat) {
        if (source.hasParam("Pawprint")) {
            List<Integer> weights = modes.stream().map(mode -> forge.game.ability.AbilityUtils.calculateAmount(
                    mode.getHostCard(), mode.getParam("Pawprint"), mode)).toList();
            return select("modes", source, "Choose modes within the pawprint budget", modes, min, max,
                    false, repeat, true, indices -> indices.stream().mapToInt(weights::get).sum() <= max,
                    Map.of("ModeBudget", max, "ModeCosts", weights));
        }
        return select("modes", source, "Choose modes", modes, min, max, false, repeat, true, null);
    }

    @Override
    public Integer announceRequirements(SpellAbility source, int min, int max, String name) {
        Cost cost = source.getPayCosts();
        if ("X".equals(name) && cost != null && !isFullControl(FullControlFlag.AllowPaymentStartWithMissingResources)) {
            Integer limit = cost.getMaxForNonManaX(source, player, false);
            if (limit != null) { max = Math.min(max, limit); }
        }
        if (min > max) { return null; }
        return number(source, "Announce " + name, min, max, cost == null || !cost.isMandatory());
    }

    @Override
    public int chooseNumber(SpellAbility source, String prompt, int min, int max) {
        return number(source, prompt, min, max, false);
    }

    @Override
    public int chooseNumber(SpellAbility source, String prompt, List<Integer> values, Player relatedPlayer) {
        return one("number_options", source, prompt, values);
    }

    @Override
    public int chooseNumberForCostReduction(SpellAbility source, int min, int max) {
        return number(source, "Choose the cost reduction", min, max, false);
    }

    @Override
    public int chooseNumberForKeywordCost(SpellAbility source, Cost cost, KeywordInterface keyword, String prompt, int max) {
        return max <= 0 ? 0 : number(source, prompt, 0, max, false);
    }

    @Override
    public boolean chooseBinary(SpellAbility source, String prompt, BinaryChoiceType kind, Boolean defaultChoice) {
        return one("binary_" + kind.name(), source, prompt, List.of(true, false));
    }

    @Override
    public boolean confirmAction(SpellAbility source, PlayerActionConfirmMode mode, String prompt,
            List<String> labels, Card cardToShow, Map<String, Object> params) {

        Map<String, Object> context = params == null ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(params);
        if (cardToShow != null) { context.put("Card", cardToShow); }
        if (labels != null && !labels.isEmpty()) { context.put("Labels", labels); }
        try {
            if (cardToShow != null) { tempShowCards(List.of(cardToShow)); }
            return select("confirm", source, prompt, List.of(true, false), 1, 1,
                    false, false, false, null, context).get(0);
        } finally { endTempShowCards(); }
    }

    @Override
    public void tempShowCards(Iterable<Card> cards) {
        for (Card card : cards) {
            for (Player viewer : player != observer && player.getControllingPlayer() == observer
                    ? List.of(player, observer) : List.of(player)) {
                if (!card.mayPlayerLook(viewer)) {
                    card.addMayLookTemp(viewer);
                    temporaryViews.computeIfAbsent(viewer, ignored -> new CardCollection()).add(card);
                }
            }
            if (card.getZone() != null) {
                reveal(new CardCollection(card), card.getZone().getZoneType(), card.getOwner(), "", false);
            }
        }
    }

    @Override
    public void endTempShowCards() {
        temporaryViews.forEach((viewer, cards) -> cards.forEach(card -> card.removeMayLookTemp(viewer)));
        temporaryViews.clear();
    }

    private <T extends GameEntity> List<T> effectEntities(String kind, FCollectionView<T> options,
            int minimum, int maximum, boolean optional, DelayedReveal delayedReveal,
            SpellAbility source, String prompt, Player relatedPlayer, Map<String, Object> params) {
        if (minimum < 0 || maximum < minimum) { throw invalidRequest(kind + ": invalid bounds"); }
        Map<String, Object> context = params == null ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(params);
        if (relatedPlayer != null) { context.put("RelatedPlayer", relatedPlayer); }
        try {
            for (T option : options) {
                if (option instanceof Card card) { tempShowCards(List.of(card)); }
            }
            if (delayedReveal != null) {
                CardCollection disclosed = new CardCollection();
                for (CardView view : delayedReveal.getCards()) {
                    Card card = getGame().findByView(view);
                    if (card == null) { throw invalidRequest(kind + ": detached disclosure"); }
                    disclosed.add(card);
                }
                tempShowCards(disclosed);
                context.put("Revealed", disclosed);
                reveal(delayedReveal);
            }
            if (!optional && minimum == 1 && maximum == 1 && options.size() == 1) {
                return List.of(options.get(0));
            }
            List<T> candidates = new java.util.ArrayList<>(options);
            return select(kind, source, MessageUtil.formatMessage(prompt, player, relatedPlayer),
                    candidates, Math.min(minimum, options.size()), Math.min(maximum, options.size()),
                    optional, false, false,
                    answer -> answer.stream().allMatch(index -> options.contains(candidates.get(index))), context);
        } finally { endTempShowCards(); }
    }

    @Override
    public boolean confirmPayment(CostPart part, String prompt, SpellAbility source) {
        return one("confirm_payment", source, part == null ? prompt : prompt + "\n" + part, List.of(true, false));
    }

    @Override
    public List<CostPart> orderCosts(List<CostPart> costs) {
        return select("cost_order", activeActions.peek(), "Choose cost payment order", costs,
                costs.size(), costs.size(), false, false, true, null);
    }

    @Override
    public boolean chooseTargetsFor(SpellAbility ability) {
        boolean mustTarget = true;
        for (SpellAbility parent = ability.getParent(); parent != null; parent = parent.getParent()) {
            if (parent.usesTargeting()) { mustTarget = false; break; }
        }
        return chooseTargets(ability, null, null, null, false, mustTarget);
    }

    private boolean chooseTargets(SpellAbility ability, Integer count, List<Integer> divisions,
            Predicate<GameObject> filter, boolean optional, boolean mustTarget) {
        TargetRestrictions restrictions = ability.getTargetRestrictions();
        int min = count == null ? ability.getMinTargets() : count;
        int max = count == null ? ability.getMaxTargets() : count;
        if (min == 0 && max == 0) { return true; }
        if (restrictions.isRandomTarget() && count == null) {
            List<GameEntity> candidates = restrictions.getAllCandidates(ability);
            if (candidates.size() < min) { return false; }
            int amount = restrictions.isRandomNumTargets()
                    ? forge.util.Aggregates.randomInt(Math.max(1, min), Math.min(max, candidates.size())) : min;
            for (int i = 0; i < amount; i++) {
                GameEntity target = forge.util.Aggregates.random(candidates);
                candidates.remove(target);
                ability.getTargets().add(target);
            }
            return ability.isTargetNumberValid();
        }
        while (ability.getTargets().size() < max) {
            if (divisions == null && ability.isDividedAsYouChoose() && ability.getStillToDivide() == 0) { break; }
            List<Card> cards = CardUtil.getValidCardsToTarget(ability);
            boolean filtered = mustTarget && forge.game.staticability.StaticAbilityMustTarget.filterMustTargetCards(player, cards, ability);
            List<Object> options = new java.util.ArrayList<>();
            for (GameEntity entity : restrictions.getAllCandidates(ability)) {
                if (entity instanceof Card card ? cards.contains(card) : !filtered) {
                    if (!ability.getTargets().contains(entity) && (filter == null || filter.test(entity))) { options.add(entity); }
                }
            }
            if (restrictions.getZone().contains(ZoneType.Stack)) {
                for (SpellAbilityStackInstance entry : getGame().getStack()) {
                    SpellAbility candidate = entry.getSpellAbility();
                    if (!ability.getTargets().contains(candidate) && ability.canTarget(candidate) && (filter == null || filter.test(candidate))) { options.add(candidate); }
                }
            }
            boolean enough = ability.getTargets().size() >= min && ability.isTargetNumberValid();
            boolean dividedDone = !ability.isDividedAsYouChoose() || (divisions == null ? ability.getStillToDivide() == 0 : divisions.isEmpty());
            if (enough && dividedDone && !filtered) { options.add("finish"); }
            if (options.isEmpty()) { return enough && dividedDone; }
            List<Object> chosen = select("target", ability, "Choose the next target", options, 1, 1,
                    optional || !(ability.isTrigger() || restrictions.getMandatory()), false, false, null);
            if (chosen.isEmpty()) { return false; }
            if (chosen.get(0) instanceof String) { break; }
            GameObject target = (GameObject) chosen.get(0);
            if (!ability.canTarget(target) || !ability.getTargets().add(target)) {
                throw new IllegalArgumentException("Native target is no longer accepted");
            }
            if (ability.isDividedAsYouChoose()) {
                int amount;
                if (divisions != null) {
                    amount = one("target_division", ability, "Choose allocation for " + observation.text(target), divisions);
                    divisions.remove(Integer.valueOf(amount));
                } else {
                    int required = Math.max(0, min - ability.getTargets().size());
                    int upper = ability.getStillToDivide() - required;
                    int lower = ability.getTargets().size() == max ? upper : 1;
                    amount = number(ability, "Allocate to " + observation.text(target), lower, upper, false);
                }
                ability.addDividedAllocation(target, amount);
            }
        }
        return ability.getTargets().size() >= min && ability.isTargetNumberValid()
                && (!ability.isDividedAsYouChoose() || (divisions == null ? ability.getStillToDivide() == 0 : divisions.isEmpty()));
    }

    @Override
    public byte chooseColor(String prompt, SpellAbility source, ColorSet colors) {
        if (colors.isColorless()) { return 0; }
        List<Byte> options = new java.util.ArrayList<>();
        for (forge.card.MagicColor.Color color : colors) { options.add(color.getColorMask()); }
        return one("color", source, prompt, options);
    }

    @Override
    public Mana chooseManaFromPool(List<Mana> options) {
        return one("mana_pool", activeActions.peek(), "Choose mana from the pool", options);
    }



    @Override
    public boolean payManaCost(ManaCost cost, CostPartMana part, SpellAbility ability,
            String prompt, ManaConversionMatrix matrix, boolean effect) {
        return PlaySpellAbility.payManaCost(this, cost, part, ability, player, prompt, matrix, effect);
    }

    @Override
    public boolean applyManaToCost(ManaCostBeingPaid cost, SpellAbility ability,
            String prompt, ManaConversionMatrix matrix, boolean effect) {
        if (matrix != null) { player.getManaPool().applyCardMatrix(matrix); }
        boolean mandatory = false;
        if (ability.getPayCosts() != null && ability.getPayCosts().isMandatory()) {
            List<Mana> refund = new java.util.ArrayList<>();
            mandatory = player.getManaPool().payManaCostFromPool(new ManaCostBeingPaid(cost), ability, true, refund);
            player.getManaPool().refundMana(refund);
        }
        player.pushPaidForSA(ability);
        ability.setManaCostBeingPaid(cost);
        int life = 0;
        try {
            while (!cost.isPaid()) {
                if (matrix != null) { player.getManaPool().applyCardMatrix(matrix); }
                List<Object> options = new java.util.ArrayList<>();
                for (Mana mana : player.getManaPool()) {
                    if (mana.meetsManaRestrictions(ability)
                            && ability.allowsPayingWithShard(mana.getSourceCard(), mana.getColor())
                            && cost.isNeeded(mana, player.getManaPool())) { options.add(mana); }
                }
                CardCollection sources = new CardCollection(getGame().getCardsIn(ZoneType.Battlefield));
                sources.addAll(player.getCardsIn(ZoneType.Hand));
                sources.addAll(player.getCardsActivatableInExternalZones(true));
                for (Card card : sources) {
                    for (SpellAbility manaAbility : card.getAllPossibleAbilities(player, true)) {
                        if (manaAbility.isManaAbility() && !activeActions.contains(manaAbility)) { options.add(manaAbility); }
                    }
                }
                boolean lifeInstead = player.hasKeyword("PayLifeInsteadOf:B")
                        && cost.hasAnyKind(forge.card.mana.ManaAtom.BLACK);
                if ((cost.containsPhyrexianMana() || lifeInstead) && player.canPayLife(life + 2, effect, ability)) {
                    options.add("pay_2_life");
                }
                if (!mandatory) { options.add("cancel"); }
                if (options.isEmpty()) { return false; }
                Object chosen = one("mana_payment", ability, (prompt == null ? "Pay mana" : prompt) + ": " + cost, options);
                if (chosen instanceof Mana mana) {
                    if (!player.getManaPool().tryPayCostWithMana(ability, cost, mana, false)) {
                        throw new IllegalStateException("Native mana spending was rejected");
                    }
                    ability.getPayingMana().add(mana);
                } else if (chosen instanceof SpellAbility manaAbility) {
                    playChosenSpellAbility(manaAbility);
                } else if ("pay_2_life".equals(chosen)) {
                    if (cost.payPhyrexian()) { ability.setSpendPhyrexianMana(true); }
                    else { cost.decreaseShard(ManaCostShard.BLACK, 1); }
                    life += 2;
                } else { return false; }
            }
            if (life > 0) { player.payLife(life, ability, effect); }
            return true;
        } finally {
            ability.setManaCostBeingPaid(null);
            player.popPaidForSA();
        }
    }

    @Override
    public CostDecisionMakerBase getCostDecisionMaker(Player payer, SpellAbility ability, boolean effect, String prompt) {
        if (payer != player) { throw new IllegalArgumentException("Cost payer mismatch"); }
        return new AgentCostDecision(this, payer, ability, effect, prompt);
    }

    @Override
    public void reveal(CardCollectionView cards, ZoneType zone, Player owner, String message, boolean suffix) {
        if (player != observer && player.getControllingPlayer() != observer) { return; }
        for (Card card : cards) {
            String name = observation.disclosedName(card.getView());
            if (name != null && !name.isEmpty()) {
                callback.cardsRevealed(card.getOwner() == observer, zone.name(), List.of(observation.alias(card.getId())), List.of(name), List.of(card.isToken()));
            }
        }
    }

    @Override
    public void reveal(List<CardView> cards, ZoneType zone, PlayerView owner, String message, boolean suffix) {
        if (player != observer && player.getControllingPlayer() != observer) { return; }
        for (CardView card : cards) {
            final String name = observation.disclosedName(card);
            if (name != null && !name.isEmpty()) {
                callback.cardsRevealed(card.getOwner().getId() == observer.getId(), zone.name(),
                        List.of(observation.alias(card.getId())), List.of(name), List.of(card.isToken()));
            }
        }
    }

    private void publishLibraryTop(CardCollectionView cards) {
        if (cards.isEmpty()) { return; }
        List<Integer> ids = new java.util.ArrayList<>();
        List<String> names = new java.util.ArrayList<>();
        List<Boolean> tokens = new java.util.ArrayList<>();
        for (Card card : cards) {
            final String name = observation.visibleName(card.getView());
            if (name == null) { break; }
            ids.add(observation.alias(card.getId())); names.add(name); tokens.add(card.isToken());
        }
        callback.libraryTopVisible(cards.getFirst().getOwner() == observer, ids, names, tokens);
    }

    @Override
    public void awaitNextInput() {
        }
    @Override
    public void cancelAwaitNextInput() {
        }
    @Override
    public void autoPassCancel() {
        }
    @Override
    public boolean isOrderedZone() {
        return true;
    }

    public int getRawCandidateCount() {
        return rawCandidateCount;
    }
    public int getChosenActionCount() {
        return chosenActionCount;
    }
    public int getChosenPassActionCount() {
        return chosenPassActionCount;
    }
    public int getFailedActionCount() {
        return failedActionCount;
    }
    public int getMulligansTaken() {
        return player.getStats().getMulliganCount();
    }
    public int getCardsBottomed() {
        return cardsBottomed;
    }
    public int getNativeRequestCount() {
        return requests;
    }

    @Override
    public void playSpellAbilityNoStack(SpellAbility ability, boolean mayChooseNewTargets) {
        activeActions.push(ability);
        try { PlaySpellAbility.playSpellAbilityNoStack(this, player, ability, !mayChooseNewTargets); }
        finally { activeActions.pop(); }
    }

    @Override
    public List<SpellAbility> orderSimultaneousSa(List<SpellAbility> abilities) {
        if (abilities.size() < 2) { return List.copyOf(abilities); }
        return select("stack_order", activeActions.peek(), "Order simultaneous actions: resolves first to last",
                abilities, abilities.size(), abilities.size(), false, false, true, null);
    }

    @Override
    public void orderAndPlaySimultaneousSa(List<SpellAbility> abilities) {
        {
            List<SpellAbility> ordered = orderSimultaneousSa(abilities);
            for (int i = ordered.size() - 1; i >= 0; i--) {
                SpellAbility next = ordered.get(i);
                activeActions.push(next);
                try {
                    if (next.isTrigger() && !next.isCopied()) {
                        PlaySpellAbility.playSpellAbility(this, player, next);
                    } else {
                        if (next.isCopied()) {
                            if (next.isSpell()) {
                                if (!next.getHostCard().isInZone(ZoneType.Stack)) {
                                    next.setHostCard(getGame().getAction().moveToStack(next.getHostCard(), next));
                                } else { getGame().getStackZone().add(next.getHostCard()); }
                            }
                            if (next.isMayChooseNewTargets()) { next.setupNewTargets(player); }
                        }
                        getGame().getStack().add(next);
                    }
                } finally { activeActions.pop(); }
            }
        }
    }

    @Override
    public boolean playTrigger(Card host, WrappedAbility ability, boolean mandatory) {
        activeActions.push(ability);
        try { return PlaySpellAbility.playSpellAbilityNoStack(this, player, ability); }
        finally { activeActions.pop(); }
    }

    @Override
    public boolean playSaFromPlayEffect(SpellAbility ability) {
        activeActions.push(ability);
        try { return PlaySpellAbility.playSpellAbility(this, player, ability); }
        finally { activeActions.pop(); }
    }
    @Override
    public List<PaperCard> sideboard(final Deck deck, GameType gameType, String message) {
        List<PaperCard> pool = new java.util.ArrayList<>(deck.get(DeckSection.Main).toFlatList());
        int originalMain = pool.size();
        if (deck.get(DeckSection.Sideboard) != null) { pool.addAll(deck.get(DeckSection.Sideboard).toFlatList()); }
        int minimum = Math.min(originalMain, gameType.getDeckFormat().getMainRange().getMinimum());
        var sideRange = gameType.getDeckFormat().getSideRange();
        if (sideRange != null) { minimum = Math.max(minimum, pool.size() - sideRange.getMaximum()); }
        return select("sideboard", null, message, pool, minimum, pool.size(), false, false, false, null);
    }

    @Override
    public List<PaperCard> chooseCardsYouWonToAddToDeck(List<PaperCard> losses) {
        return select("won_cards", null, "Choose cards to add to the deck", losses, 0, losses.size(), false, false, false, null);
    }
    @Override
    public Map<Card, Integer> assignCombatDamage(Card source, CardCollectionView recipients,
            CardCollectionView remaining, int damage, GameEntity defender, boolean overrideOrder) {
        boolean divide = source.hasKeyword("You may assign CARDNAME's combat damage divided as you choose among "
                + "defending player and/or any number of creatures they control.") && overrideOrder;
        boolean toDefender = defender != null && (source.hasKeyword(Keyword.TRAMPLE) || divide);
        if (recipients.size() <= 1 && !toDefender) {
            Map<Card, Integer> forced = new java.util.LinkedHashMap<>();
            forced.put(recipients.isEmpty() ? null : recipients.get(0), damage);
            return forced;
        }
        Map<Card, Integer> assigned = new java.util.LinkedHashMap<>();
        int left = damage;
        while (true) {
            List<Object> options = new java.util.ArrayList<>();
            if (left == 0) { options.add("finish"); }
            if (left < damage) { options.add("reset"); }
            if (remaining != null && remaining.size() > 1 && source.isAttacking()) { options.add("skip"); }
            boolean lethal = true;
            for (Card card : recipients) {
                if (left > 0 && (overrideOrder || divide || lethal)) { options.add(card); }
                lethal &= assigned.getOrDefault(card, 0) >= card.getExcessDamageValue(source.hasKeyword(Keyword.DEATHTOUCH));
            }
            if (left > 0 && toDefender && (divide || lethal)) { options.add(defender); }
            Object chosen = one("combat_damage", null, "Assign " + left + " remaining damage from " + observation.text(source), options);
            if ("finish".equals(chosen)) { return assigned; }
            if ("skip".equals(chosen)) { return null; }
            if ("reset".equals(chosen)) { assigned.clear(); left = damage; continue; }
            int amount = number(null, "Damage to " + observation.text(chosen), 1, left, false);
            assigned.merge(chosen == defender ? null : (Card) chosen, amount, Integer::sum);
            left -= amount;
        }
    }
    @Override
    public Map<GameEntity, Integer> divideShield(Card effectSource, Map<GameEntity, Integer> affected, int shieldAmount) {
        Map<GameEntity, Integer> result = new java.util.LinkedHashMap<>();
        int remaining = Math.min(shieldAmount, affected.values().stream().mapToInt(Integer::intValue).sum());
        while (remaining > 0) {
            List<GameEntity> candidates = affected.keySet().stream()
                    .filter(e -> result.getOrDefault(e, 0) < affected.get(e)).toList();
            GameEntity target = one("shield_target", null, "Choose a shield recipient", candidates);
            int amount = number(null, "Shield amount for " + observation.text(target), 1,
                    Math.min(remaining, affected.get(target) - result.getOrDefault(target, 0)), false);
            result.merge(target, amount, Integer::sum);
            remaining -= amount;
        }
        return result;
    }

    @Override
    public Map<Byte, Integer> specifyManaCombo(SpellAbility sa, ColorSet colorSet, int manaAmount, boolean different) {
        if (manaAmount == 0) { return Map.of(); }
        List<Byte> colors = new java.util.ArrayList<>();
        for (MagicColor.Color color : colorSet.getOrderedColors()) {
            if (color != MagicColor.Color.COLORLESS) { colors.add(color.getColorMask()); }
        }
        if (manaAmount < 0 || colors.isEmpty() || different && manaAmount > colors.size()) {
            throw invalidRequest("specifyManaCombo: invalid native bounds");
        }
        Map<Byte, Integer> result = new java.util.HashMap<>();
        for (byte color : select("color", sa, "Choose " + manaAmount + " mana colors", colors,
                manaAmount, manaAmount, false, !different, false, null)) {
            result.merge(color, 1, Integer::sum);
        }
        return result;
    }
    @Override
    public CardCollectionView choosePermanentsToSacrifice(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message) {
        return new CardCollection(effectEntities("sacrifice", validTargets, min, max, min == 0, null, sa, message, null, null));
    }
    @Override
    public CardCollectionView choosePermanentsToDestroy(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message) {
        return new CardCollection(effectEntities("destroy", validTargets, min, max, min == 0, null, sa, message, null, null));
    }
    @Override
    public TargetChoices chooseNewTargetsFor(SpellAbility ability, Predicate<GameObject> filter, boolean optional) {
        SpellAbility source = ability instanceof WrappedAbility wrapped ? wrapped.getWrappedAbility() : ability;
        if (!source.usesTargeting()) { return null; }
        TargetChoices old = source.getTargets();
        source.clearTargets();
        List<Integer> divisions = source.isDividedAsYouChoose() ? new java.util.ArrayList<>(old.getDividedValues()) : null;
        if (chooseTargets(source, old.size(), divisions, filter, optional, false)) { return source.getTargets(); }
        source.setTargets(old);
        return null;
    }

    @Override
    public Pair<SpellAbilityStackInstance, GameObject> chooseTarget(SpellAbility sa, List<Pair<SpellAbilityStackInstance, GameObject>> allTargets) {
        return optional("redirect_target", sa, "Choose a target to redirect", allTargets);
    }
    @Override
    public boolean helpPayForAssistSpell(ManaCostBeingPaid cost, SpellAbility sa, int max, int requested) {
        int amount = chooseNumber(sa, "How much generic mana to pay for assist?", 0, max);
        if (amount == 0) { return true; }
        if (!applyManaToCost(new ManaCostBeingPaid(ManaCost.get(amount)), sa, "Pay for assist", null, true)) { return false; }
        cost.decreaseGenericMana(amount);
        return true;
    }

    @Override
    public Player choosePlayerToAssistPayment(FCollectionView<Player> optionList, SpellAbility sa, String title, int max) {
        return chooseSingleEntityForEffect(optionList, null, sa, title, true, null, null);
    }
    @Override
    public CardCollectionView chooseCardsForEffect(CardCollectionView sourceList, SpellAbility sa, String title, int min, int max, boolean isOptional, Map<String, Object> params) {
        return new CardCollection(effectEntities("effect_cards", sourceList, min, max, isOptional, null, sa, title, null, params));
    }
    @Override
    public CardCollection chooseCardsForEffectMultiple(Map<String, CardCollection> validMap, SpellAbility sa, String title, boolean isOptional) {
        CardCollection result = new CardCollection();
        validMap.forEach((group, cards) -> result.addAll(chooseCardsForEffect(cards, sa, title + " (" + group + ")", 0, 1, isOptional, null)));
        return result;
    }

    @Override
    public <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> optionList, DelayedReveal delayedReveal, SpellAbility sa, String title, boolean isOptional, Player relatedPlayer, Map<String, Object> params) {
        List<T> chosen = effectEntities("effect_entity", optionList, 1, 1, isOptional, delayedReveal, sa, title, relatedPlayer, params);
        return chosen.isEmpty() ? null : chosen.get(0);
    }
    @Override
    public <T extends GameEntity> List<T> chooseEntitiesForEffect(FCollectionView<T> optionList, int min, int max, DelayedReveal delayedReveal, SpellAbility sa, String title, Player relatedPlayer, Map<String, Object> params) {
        return effectEntities("effect_entities", optionList, min, max, min == 0, delayedReveal, sa, title, relatedPlayer, params);
    }
    @Override
    public List<SpellAbility> chooseSpellAbilitiesForEffect(List<SpellAbility> spells, SpellAbility sa, String title, int num, Map<String, Object> params) {
        return select("effect_actions", sa, title, spells, num, num, false, false, false, null, params);
    }
    @Override
    public SpellAbility chooseSingleSpellForEffect(List<SpellAbility> spells, SpellAbility sa, String title, Map<String, Object> params) {
        return spells.isEmpty() ? null : select("effect_action", sa, title, spells, 1, 1, false, false, false, null, params).get(0);
    }
    @Override
    public boolean confirmBidAction(SpellAbility sa, PlayerActionConfirmMode bidlife, String string, int bid, Player winner) {
        return one("bid", sa, string + " / " + bid + " / " + winner, List.of(true, false));
    }
    @Override
    public boolean confirmReplacementEffect(ReplacementEffect replacementEffect, SpellAbility effectSA, GameEntity affected, String question) {
        return one("replacement", effectSA, question, List.of(true, false));
    }
    @Override
    public boolean confirmStaticApplication(Card hostCard, PlayerActionConfirmMode mode, String message, String logic) {
        return one("static_application", null, message, List.of(true, false));
    }
    @Override
    public boolean confirmTrigger(WrappedAbility ability) {
        return one("confirm_trigger", ability, "Resolve this optional trigger?", List.of(true, false));
    }
    @Override
    public List<Card> exertAttackers(List<Card> attackers) {
        return select("exertAttackers", null, "exertAttackers", attackers, 0, attackers.size(), false, false, false, null);
    }
    @Override
    public List<Card> enlistAttackers(List<Card> attackers) {
        return select("enlistAttackers", null, "enlistAttackers", attackers, 0, attackers.size(), false, false, false, null);
    }
    private boolean legalAttackChoice(Object value, Player attacker, Combat combat) {
        if (value instanceof String) { return CombatUtil.validateAttackers(combat); }
        NativeDecision.CombatAssignment choice = (NativeDecision.CombatAssignment) value;
        if (choice.remove()) { return combat.isAttacking(choice.creature(), choice.target()); }
        return attacker.getCreaturesInPlay().contains(choice.creature())
                && combat.getDefenders().contains(choice.target())
                && !combat.isAttacking(choice.creature(), choice.target())
                && CombatUtil.canAttack(choice.creature(), choice.target());
    }

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        while (true) {
            List<Object> options = new java.util.ArrayList<>();
            if (legalAttackChoice("finish", attacker, combat)) { options.add("finish"); }
            for (Card creature : attacker.getCreaturesInPlay()) {
                for (GameEntity defender : combat.getDefenders()) {
                    var choice = new NativeDecision.CombatAssignment(creature, defender, false);
                    if (legalAttackChoice(choice, attacker, combat)) { options.add(choice); }
                }
            }
            for (Card creature : combat.getAttackers()) {
                options.add(new NativeDecision.CombatAssignment(creature, combat.getDefenderByAttacker(creature), true));
            }
            Object value = select("declare_attackers", null, "Assign attackers or finish the declaration", options,
                    1, 1, false, false, false,
                    answer -> legalAttackChoice(options.get(answer.get(0)), attacker, combat)).get(0);
            if (value instanceof String) { return; }
            var choice = (NativeDecision.CombatAssignment) value;
            if (choice.remove()) { combat.removeFromCombat(choice.creature()); }
            else { combat.addAttacker(choice.creature(), choice.target()); }
            getGame().fireEvent(new GameEventCombatChanged());
        }
    }

    private boolean legalBlockChoice(Object value, Player defender, Combat combat) {
        if (value instanceof String) { return CombatUtil.validateBlocks(combat, defender) == null; }
        NativeDecision.CombatAssignment choice = (NativeDecision.CombatAssignment) value;
        Card attacker = (Card) choice.target();
        if (choice.remove()) { return combat.isBlocking(choice.creature(), attacker); }
        return defender.getCreaturesInPlay().contains(choice.creature())
                && combat.isAttacking(attacker)
                && CombatUtil.canBlock(attacker, choice.creature(), combat);
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        while (true) {
            List<Object> options = new java.util.ArrayList<>();
            if (legalBlockChoice("finish", defender, combat)) { options.add("finish"); }
            for (Card creature : defender.getCreaturesInPlay()) {
                for (Card attacker : combat.getAttackers()) {
                    var choice = new NativeDecision.CombatAssignment(creature, attacker, combat.isBlocking(creature, attacker));
                    if (legalBlockChoice(choice, defender, combat)) { options.add(choice); }
                }
            }
            Object value = select("declare_blockers", null, "Assign blockers or finish the declaration", options,
                    1, 1, false, false, false,
                    answer -> legalBlockChoice(options.get(answer.get(0)), defender, combat)).get(0);
            if (value instanceof String) { return; }
            var choice = (NativeDecision.CombatAssignment) value;
            if (choice.remove()) { combat.removeBlockAssignment((Card) choice.target(), choice.creature()); }
            else { combat.addBlocker((Card) choice.target(), choice.creature()); }
            getGame().fireEvent(new GameEventCombatChanged());
        }
    }
    @Override
    public CardCollection orderBlockers(Card attacker, CardCollection blockers) {
        return new CardCollection(select("combat_order", null, "orderBlockers", new java.util.ArrayList<>(blockers), blockers.size(), blockers.size(), false, false, true, null));
    }
    @Override
    public CardCollection orderBlocker(final Card attacker, final Card blocker, final CardCollection oldBlockers) {
        CardCollection ordered = new CardCollection(oldBlockers);
        ordered.add(number(null, "Choose the new blocker's position", 0, ordered.size(), false), blocker);
        return ordered;
    }

    @Override
    public CardCollection orderAttackers(Card blocker, CardCollection attackers) {
        return new CardCollection(select("combat_order", null, "orderAttackers", new java.util.ArrayList<>(attackers), attackers.size(), attackers.size(), false, false, true, null));
    }
    @Override
    public void notifyOfValue(SpellAbility source, GameObject related, String value) {
        // Scalar-choice memory is outside the current observation contract.
    }
    private ImmutablePair<CardCollection, CardCollection> arrangeLibrary(String kind, CardCollection topN, ZoneType otherZone) {
        Map<String, Object> context = new java.util.LinkedHashMap<>();
        context.put("Destination", otherZone);
        SpellAbility resolving = activeActions.peek();
        if (resolving == null && getGame().getStack().isResolving()) { resolving = getGame().getStack().peekAbility(); }
        if (resolving != null) { context.put("ResolvingAction", resolving); }
        context.put("OrderDirection", otherZone == ZoneType.Library ? "top_first" : "bottom_first");
        try {
            tempShowCards(topN);
            publishLibraryTop(topN);
            CardCollection moved = new CardCollection(select(kind + (otherZone == ZoneType.Library ? "_bottom" : "_graveyard"),
                    null, otherZone == ZoneType.Library ? "Choose cards for the bottom, in final top-to-bottom order"
                            : "Choose cards for the graveyard, bottom first",
                    new java.util.ArrayList<>(topN), 0, topN.size(), true, false, true, null, context));
            CardCollection kept = new CardCollection(topN);
            kept.removeAll(moved);
            context.put("Destination", ZoneType.Library);
            context.put("OrderDirection", "top_first");
            if (kept.size() > 1) {
                kept = new CardCollection(select(kind + "_top", null, "Order the kept cards, top first",
                        new java.util.ArrayList<>(kept), kept.size(), kept.size(), false, false, true, null, context));
            }
            return ImmutablePair.of(kept, moved);
        } finally {
            if (!topN.isEmpty()) {
                callback.libraryTopVisible(topN.getFirst().getOwner() == observer,
                        List.of(), List.of(), List.of());
            }
            endTempShowCards();
        }
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForScry(CardCollection topN) {
        return arrangeLibrary("scry", topN, ZoneType.Library);
    }
    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN) {
        return arrangeLibrary("surveil", topN, ZoneType.Graveyard);
    }
    @Override
    public boolean willPutCardOnTop(Card c) {
        return select("put_on_top", null, "Put this card on top?", List.of(true, false),
                1, 1, false, false, false, null, Map.of("Card", c)).get(0);
    }
    @Override
    public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destination, SpellAbility source) {

        try {
            tempShowCards(cards);
            if (cards.size() < 2) { return cards; }
            boolean topFirst = orderedMoveToTopOfLibrary(destination, source);
            List<Card> ordered = select(destination == ZoneType.Library ? "library_order" : "graveyard_order", source,
                    destination == ZoneType.Library ? "Order cards going to the library, " + (topFirst ? "top first" : "bottom first")
                            : "Order cards going to the graveyard, bottom first",
                    new java.util.ArrayList<>(cards), cards.size(), cards.size(), false, false, true, null,
                    Map.of("Destination", destination, "OrderDirection", topFirst ? "top_first" : "bottom_first"));
            if (topFirst) { java.util.Collections.reverse(ordered); }
            return new CardCollection(ordered);
        } finally { endTempShowCards(); }
    }
    @Override
    public CardCollectionView chooseCardsToDiscardFrom(Player playerDiscard, SpellAbility sa, CardCollection validCards, int min, int max, CardCollectionView visibleToChooser) {
        return new CardCollection(select("discard", sa, "Choose cards to discard", new java.util.ArrayList<>(validCards), min, max, false, false, true, null));
    }
    @Override
    public CardCollectionView chooseCardsToDiscardUnlessType(int min, CardCollectionView hand, String[] unlessTypes, SpellAbility sa) {
        List<Card> options = new java.util.ArrayList<>(hand);
        int required = Math.min(min, options.size());
        return new CardCollection(select("discard_unless", sa, "Discard " + min + " cards or a " + String.join("/", unlessTypes),
                options, Math.min(1, required), required, false, false, false,
                indices -> indices.size() >= required || indices.stream().map(options::get)
                        .anyMatch(c -> c.isValid(unlessTypes, sa.getActivatingPlayer(), sa.getHostCard(), sa))));
    }

    @Override
    public CardCollectionView chooseCardsToDiscardToMaximumHandSize(int count) {
        return new CardCollection(select("discard_to_hand_size", null, "Discard to maximum hand size",
                new java.util.ArrayList<>(player.getCardsIn(ZoneType.Hand)), count, count, false, false, false, null));
    }
    @Override
    public CardCollectionView chooseCardsToDelve(int genericAmount, CardCollection grave) {
        return new CardCollection(select("delve", player.getPaidForSA(), "Choose cards to delve", new java.util.ArrayList<>(grave), 0, Math.min(genericAmount, grave.size()), false, false, false, null));
    }
    @Override
    public Map<Card, ManaCostShard> chooseCardsForConvokeOrImprovise(SpellAbility sa, ManaCost manaCost, CardCollectionView untappedCards, boolean artifacts, boolean creatures, Integer maxReduction) {
        ManaCostBeingPaid remaining = new ManaCostBeingPaid(manaCost);
        Map<Card, ManaCostShard> chosen = new java.util.LinkedHashMap<>();
        while (!remaining.isPaid() && (maxReduction == null || chosen.size() < maxReduction)) {
            List<Card> candidates = new java.util.ArrayList<>();
            for (Card card : untappedCards) {
                if (chosen.containsKey(card)) { continue; }
                List<Byte> colors = new java.util.ArrayList<>();
                colors.add((byte) 0);
                if (!artifacts) { for (MagicColor.Color color : card.getColor()) { colors.add(color.getColorMask()); } }
                if (colors.stream().anyMatch(c -> new ManaCostBeingPaid(remaining).payManaViaConvoke(c) != null)) { candidates.add(card); }
            }
            Card card = optional("convoke", sa, "Choose a card to tap for " + remaining + ", or finish", candidates);
            if (card == null) { break; }
            List<Byte> colors = new java.util.ArrayList<>();
            colors.add((byte) 0);
            if (!artifacts) { for (MagicColor.Color color : card.getColor()) { colors.add(color.getColorMask()); } }
            colors.removeIf(c -> new ManaCostBeingPaid(remaining).payManaViaConvoke(c) == null);
            byte color = one("convoke_color", sa, "Choose the convoke color", colors);
            chosen.put(card, remaining.payManaViaConvoke(color));
        }
        return chosen;
    }

    @Override
    public List<Card> chooseCardsForSplice(SpellAbility sa, List<Card> cards) {
        return select("chooseCardsForSplice", null, "chooseCardsForSplice", cards, 0, cards.size(), false, false, false, null);
    }
    @Override
    public CardCollectionView chooseCardsToRevealFromHand(int min, int max, CardCollectionView valid) {
        return new CardCollection(select("reveal_hand", null, "Choose cards to reveal", new java.util.ArrayList<>(valid), min, max, false, false, false, null));
    }
    @Override
    public String chooseSomeType(String kindOfType, SpellAbility sa, Collection<String> validTypes, boolean isOptional) {
        return isOptional ? optional("type", sa, "Choose " + kindOfType, new java.util.ArrayList<>(validTypes)) : one("type", sa, "Choose " + kindOfType, new java.util.ArrayList<>(validTypes));
    }
    @Override
    public String chooseSector(Card assignee, String ai, List<String> sectors) {
        return one("chooseSector", null, "chooseSector", sectors);
    }
    @Override
    public List<Card> chooseContraptionsToCrank(List<Card> contraptions) {
        return select("chooseContraptionsToCrank", null, "chooseContraptionsToCrank", contraptions, 0, contraptions.size(), false, false, false, null);
    }
    @Override
    public int chooseSprocket(Card assignee, List<Integer> sprockets) {
        return one("chooseSprocket", null, "chooseSprocket", sprockets);
    }
    @Override
    public PlanarDice choosePDRollToIgnore(List<PlanarDice> rolls) {
        return one("choosePDRollToIgnore", null, "choosePDRollToIgnore", rolls);
    }
    @Override
    public Integer chooseRollToIgnore(List<Integer> rolls) {
        return one("chooseRollToIgnore", null, "chooseRollToIgnore", rolls);
    }
    @Override
    public List<Integer> chooseDiceToReroll(List<Integer> rolls) {
        return select("chooseDiceToReroll", null, "chooseDiceToReroll", rolls, 0, rolls.size(), false, false, false, null);
    }
    @Override
    public Integer chooseRollToModify(List<Integer> rolls) {
        return optional("chooseRollToModify", null, "chooseRollToModify", rolls);
    }
    @Override
    public RollDiceEffect.DieRollResult chooseRollToSwap(List<RollDiceEffect.DieRollResult> rolls) {
        return optional("chooseRollToSwap", null, "chooseRollToSwap", rolls);
    }
    @Override
    public String chooseRollSwapValue(List<String> swapChoices, Integer currentResult, int power, int toughness) {
        return optional("chooseRollSwapValue", null, "chooseRollSwapValue", swapChoices);
    }
    @Override
    public Object vote(SpellAbility sa, String prompt, List<Object> options, ListMultimap<Object, Player> votes, Player forPlayer, boolean optional) {
        return optional ? optional("vote", sa, prompt, options) : one("vote", sa, prompt, options);
    }
    @Override
    public boolean chooseFlipResult(SpellAbility sa, Player flipper, boolean call) {
        return one("coin_flip", sa, "Choose the coin flip result for " + flipper, List.of(true, false));
    }
    @Override
    public byte chooseColorAllowColorless(String message, Card c, ColorSet colors) {
        List<Byte> options = new java.util.ArrayList<>();
        options.add((byte) 0);
        for (MagicColor.Color color : colors) { options.add(color.getColorMask()); }
        return one("color", null, message, options);
    }

    @Override
    public ColorSet chooseColors(String message, SpellAbility sa, int min, int max, ColorSet options) {
        return ColorSet.fromEnums(select("colors", sa, message, new java.util.ArrayList<>(options.getOrderedColors()), min, max, false, false, false, null));
    }
    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, String message, Predicate<ICardFace> cpp, String name) {
        return one("card_name", sa, message, forge.StaticData.instance().getCommonCards().streamAllFaces().filter(cpp).toList());
    }
    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, List<ICardFace> faces, String message) {
        return one("card_face", sa, message, faces);
    }
    @Override
    public CardState chooseSingleCardState(SpellAbility sa, List<CardState> states, String message, Map<String, Object> params) {
        return select("card_state", sa, message, states, 1, 1, false, false, false, null, params).get(0);
    }
    @Override
    public boolean chooseCardsPile(SpellAbility sa, CardCollectionView pile1, CardCollectionView pile2, String faceUp) {
        try {
            if ("False".equals(faceUp)) { tempShowCards(pile1); }
            if (!"True".equals(faceUp)) { tempShowCards(pile2); }
            return select("pile", sa, "Choose a pile", List.of(true, false), 1, 1,
                    false, false, false, null,
                    Map.of("Pile1", pile1, "Pile2", pile2, "FaceUp", faceUp)).get(0);
        } finally { endTempShowCards(); }
    }
    @Override
    public CounterType chooseCounterType(List<CounterType> options, SpellAbility sa, String prompt, Map<String, Object> params) {
        if (options.size() < 2) { return options.isEmpty() ? null : options.get(0); }
        return select("counter_type", sa, prompt, options, 1, 1, false, false, false,
                indices -> options.contains(options.get(indices.get(0))), params).get(0);
    }
    @Override
    public String chooseKeywordForPump(List<String> options, SpellAbility sa, String prompt, Card tgtCard) {
        return one("keyword", sa, prompt, options);
    }
    @Override
    public ReplacementEffect chooseSingleReplacementEffect(List<ReplacementEffect> options) {
        if (options.size() == 1) { return options.get(0); }
        return one("replacement_order", null, "Choose a replacement effect", options);
    }
    @Override
    public StaticAbility chooseSingleStaticAbility(List<StaticAbility> options) {
        if (options.size() == 1) { return options.get(0); }
        return one("cost_reduction", player.getPaidForSA(), "Choose the next cost reduction", options);
    }
    @Override
    public String chooseProtectionType(SpellAbility sa, List<String> choices) {
        return one("protection", sa, "Choose protection", choices);
    }
    @Override
    public void revealAnte(String message, Multimap<Player, PaperCard> removedAnteCards) {
    }
    @Override
    public void revealAISkipCards(String message, Map<Player, Map<DeckSection, List<? extends PaperCard>>> deckCards) {  }
    @Override
    public void revealUnsupported(Map<Player, List<PaperCard>> cards) {
        if (cards.values().stream().anyMatch(list -> !list.isEmpty())) {
            throw new IllegalStateException("The native engine reports unsupported deck cards");
        }
    }
    @Override
    public boolean payCostToPreventEffect(Cost cost, SpellAbility sa, boolean alreadyPaid, FCollectionView<Player> allPayers) {

        return PlaySpellAbility.payCostDuringAbilityResolve(this, player, cost, sa, null);
    }
    @Override
    public boolean payCostDuringRoll(Cost cost, SpellAbility sa) {
        return PlaySpellAbility.payCostDuringAbilityResolve(this, player, cost, sa, null);
    }
    @Override
    public boolean payCombatCost(Card card, Cost cost, SpellAbility sa, String prompt) {
        return PlaySpellAbility.payCostDuringAbilityResolve(this, player, cost, sa, prompt);
    }
    @Override
    public CardCollectionView chooseCardsForCost(CardCollectionView optionList, SpellAbility sa, CostPartWithList cpl, int amount, boolean isOptional, String prompt) {
        if (optionList.size() < amount) { return null; }
        List<Card> chosen = select("cost_cards", sa, prompt, new java.util.ArrayList<>(optionList),
                amount, amount, isOptional, false, true, null);
        return chosen.size() == amount ? new CardCollection(chosen) : null;
    }

    @Override
    public String chooseCardName(SpellAbility sa, Predicate<ICardFace> cpp, String valid, String message) {
        return chooseSingleCardFace(sa, message, cpp, sa.getHostCard().getName()).getName();
    }
    @Override
    public String chooseCardName(SpellAbility sa, List<ICardFace> faces, String message) {
        return chooseSingleCardFace(sa, faces, message).getName();
    }
    @Override
    public Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa, CardCollection fetchList, DelayedReveal delayedReveal, String selectPrompt, boolean isOptional, Player decider) {
        return chooseSingleEntityForEffect(fetchList, delayedReveal, sa, selectPrompt, isOptional, decider,
                Map.of("Origin", origin, "Destination", destination));
    }
    @Override
    public List<Card> chooseCardsForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa, CardCollection fetchList, int min, int max, DelayedReveal delayedReveal, String selectPrompt, Player decider) {
        return effectEntities("effect_entities", fetchList, min, max, min == 0, delayedReveal, sa, selectPrompt, decider,
                Map.of("Origin", origin, "Destination", destination));
    }


}
