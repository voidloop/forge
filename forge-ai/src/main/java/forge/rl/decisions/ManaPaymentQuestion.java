package forge.rl.decisions;

import forge.rl.StateReader;
import forge.card.MagicColor;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.card.mana.ManaAtom;
import forge.game.GameStage;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostTap;
import forge.game.mana.Mana;
import forge.game.mana.ManaConversionMatrix;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fixed mana payment: final activation order, spend order and native result, without AI plans. */
public final class ManaPaymentQuestion implements DecisionRequest {
    public final Map<String, Object> data;
    final Player player;
    final SpellAbility root;
    final ManaCost cost;
    final List<SpellAbility> sources = new ArrayList<>();
    private final List<Card> cards = new ArrayList<>();
    private final List<Boolean> available = new ArrayList<>();
    private final List<Integer> initial = new ArrayList<>();

    public ManaPaymentQuestion(Player player, StateReader view, ManaCost cost, CostPartMana part,
            SpellAbility root, ManaConversionMatrix matrix, boolean effect) {
        this.player = player;
        this.root = root;
        this.cost = cost;
        var game = player.getGame();
        // shortcut: ordinary fixed-cost spells and basic lands; extend with later cost families.
        Card host = root.getHostCard();
        PriorityQuestion.requireSpell(host);
        if (!isSupportedPaymentContext(part, effect)) {
            throw new UnsupportedOperationException("Fixed basic-land payment context");
        }
        for (ManaCostShard shard : cost) {
            if (!isOrdinaryColoredShard(shard)) {
                throw new UnsupportedOperationException("Non-elementary mana cost");
            }
        }
        identityMatrix(player.getManaPool());
        if (matrix != null) { identityMatrix(matrix); }
        for (Card card : game.getCardsIn(ZoneType.Battlefield)) {
            if (isFaceUpCreature(card)) { PriorityQuestion.requireSpell(card); continue; }
            if (!isSupportedBasicLand(card)) {
                throw new UnsupportedOperationException("Basic-land payment battlefield");
            }
            if (card.getController() != player) { continue; }
            var abilities = card.getSpellAbilities().stream().filter(sa -> !sa.isLandAbility()).toList();
            if (abilities.size() != 1) { throw new UnsupportedOperationException("Multiple basic-land variants"); }
            SpellAbility ability = abilities.get(0);
            if (!isSupportedManaAbility(ability)) {
                throw new UnsupportedOperationException("Basic-land mana shape");
            }
            sources.add(ability);
            cards.add(card);
            Player previous = ability.getActivatingPlayer();
            ability.setActivatingPlayer(player);
            try { available.add(ability.canPlay()); }
            finally { ability.setActivatingPlayer(previous); }
        }
        for (Mana mana : player.getManaPool()) {
            int index = source(mana);
            if (!isSupportedInitialMana(index)) {
                throw new UnsupportedOperationException("More than one unit per basic-land source");
            }
            initial.add(index);
        }
        // Pool iteration can change during native AI evaluation; options identify units by bound source.
        initial.sort(Integer::compareTo);
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            SpellAbility ability = sources.get(i);
            items.add(Map.of("key", view.reference(ability.getHostCard()), "name", ability.getHostCard().getName(),
                    "ability", ability.getHostCard().getSpellAbilities().indexOf(ability),
                    "color", ability.getParam("Produced"), "canActivate", available.get(i), "inPool", initial.contains(i)));
        }
        data = Map.of("state", view.snapshot(), "question", Map.of("kind", "mana_payment",
                "source", view.reference(host), "cost", cost.toString(), "items", List.copyOf(items),
                "pool", List.copyOf(initial)));
    }

    private boolean isSupportedPaymentContext(CostPartMana part, boolean effect) {
        return hasLivePayer()
                && isVisibleControlledSpell()
                && hasSupportedSpellEffect()
                && hasMatchingSingleManaCost(part)
                && isFixedCostPayment(effect)
                && isOrdinaryCast()
                && hasNoPaymentActivity()
                && hasNoPendingGameState();
    }

    private boolean hasLivePayer() {
        var game = player.getGame();
        return game.getAge() == GameStage.Play && !game.isGameOver() && root.getActivatingPlayer() == player;
    }

    private boolean isVisibleControlledSpell() {
        Card host = root.getHostCard();
        return host.getGame() == player.getGame() && !host.isFaceDown() && host.getView().canBeShownTo(player.getView())
                && root.isSpell() && host.getController() == player
                && (host.isInZone(ZoneType.Hand) || host.isInZone(ZoneType.Stack));
    }

    private boolean hasSupportedSpellEffect() {
        Card host = root.getHostCard();
        return TriggerQuestion.hasSupportedTriggers(host) && host.getReplacementEffects().isEmpty()
                && root.getSubAbility() == null
                && (root.getApi() == ApiType.PermanentCreature && !root.usesTargeting()
                || root.getApi() == ApiType.DealDamage && root.usesTargeting());
    }

    private boolean hasMatchingSingleManaCost(CostPartMana part) {
        return root.getPayCosts() != null && root.getPayCosts().getCostParts().size() == 1
                && !root.getPayCosts().isMandatory()
                && cost.equals(root.getPayCosts().getTotalMana()) && cost.equals(part.getMana());
    }

    private boolean isFixedCostPayment(boolean effect) {
        return !cost.isNoCost() && cost.countX() == 0 && !effect;
    }

    private boolean isOrdinaryCast() {
        return !root.isCopied() && !root.isOffering() && !root.isEmerge() && root.getMayPlayOption() == null
                && root.getXManaCostPaid() == null && !root.hasParam("ManaConversion");
    }

    private boolean hasNoPaymentActivity() {
        return root.getPayingMana().isEmpty() && root.getPayingManaAbilities().isEmpty();
    }

    private boolean hasNoPendingGameState() {
        var game = player.getGame();
        return game.getCardsIn(ZoneType.Command).isEmpty() && game.getTriggerHandler().getDelayedTriggers().isEmpty();
    }

    private static boolean isOrdinaryColoredShard(ManaCostShard shard) {
        return shard == ManaCostShard.WHITE || shard == ManaCostShard.BLUE || shard == ManaCostShard.BLACK
                || shard == ManaCostShard.RED || shard == ManaCostShard.GREEN;
    }

    private static boolean isFaceUpCreature(Card card) {
        return card.isCreature() && !card.isFaceDown();
    }

    private boolean isSupportedBasicLand(Card card) {
        return isVisibleNonSnowBasicLand(card) && hasNoExtraLandEffects(card);
    }

    private boolean isVisibleNonSnowBasicLand(Card card) {
        return card.isBasicLand() && !card.isSnow() && !card.isFaceDown()
                && card.getView().canBeShownTo(player.getView());
    }

    private static boolean hasNoExtraLandEffects(Card card) {
        return card.getTriggers().isEmpty() && card.getStaticAbilities().isEmpty()
                && card.getReplacementEffects().isEmpty() && card.getKeywords().isEmpty();
    }

    private static boolean isSupportedManaAbility(SpellAbility ability) {
        return isSimpleManaActivation(ability) && producesOneColoredMana(ability)
                && hasTapOnlyCost(ability) && hasNoManaRestrictions(ability);
    }

    private static boolean isSimpleManaActivation(SpellAbility ability) {
        return ability.getApi() == ApiType.Mana && ability.isManaAbility() && ability.isActivatedAbility()
                && ability.getSubAbility() == null && ability.getManaPart() != null;
    }

    private static boolean producesOneColoredMana(SpellAbility ability) {
        return ability.getParamOrDefault("Produced", "").matches("[WUBRG]")
                && ability.getParamOrDefault("Amount", "1").equals("1");
    }

    private static boolean hasTapOnlyCost(SpellAbility ability) {
        return ability.getPayCosts() != null && ability.getPayCosts().getCostParts().size() == 1
                && ability.getPayCosts().getCostParts().get(0) instanceof CostTap;
    }

    private static boolean hasNoManaRestrictions(SpellAbility ability) {
        return ability.getManaPart().getManaRestrictions().isEmpty()
                && ability.getManaPart().getExtraManaRestriction().isEmpty();
    }

    private boolean isSupportedInitialMana(int index) {
        return !initial.contains(index) && !available.get(index);
    }

    static void identityMatrix(ManaConversionMatrix matrix) {
        for (byte color : ManaAtom.MANATYPES) {
            if (!preservesManaProperties(matrix, color)) {
                throw new UnsupportedOperationException("Mana conversion/restriction");
            }
        }
    }

    private static boolean preservesManaProperties(ManaConversionMatrix matrix, byte color) {
        return matrix.getPossibleColorUses(color) == color && !matrix.isSnowForColor();
    }

    public boolean isSource(SpellAbility ability) { return sources.stream().anyMatch(bound -> bound == ability); }

    int source(Mana mana) {
        for (int i = 0; i < sources.size(); i++) {
            SpellAbility ability = sources.get(i);
            if (isManaFromSource(mana, ability)) {
                return i;
            }
        }
        throw new IllegalArgumentException("Unbound mana unit");
    }

    private boolean isManaFromSource(Mana mana, SpellAbility ability) {
        return mana.getSourceCard().getId() == ability.getHostCard().getId()
                && mana.getManaAbility() == ability.getManaPart()
                && mana.getPlayer() == player && mana.getColor() == MagicColor.fromName(ability.getParam("Produced"));
    }

    boolean isBoundSourceAbility(int index, SpellAbility ability) {
        return index >= 0 && sources.get(index) == ability;
    }

    boolean hasExpectedPaymentResources(List<Integer> answer) {
        // Check the effective pool and taps as well as the public result; failed AI payments may refund natively.
        var remaining = new HashSet<>(initial);
        remaining.addAll(answer.subList(2, 2 + answer.get(1)));
        remaining.removeAll(answer.subList(2 + answer.get(1), answer.size()));
        List<Integer> pool = new ArrayList<>();
        player.getManaPool().forEach(mana -> pool.add(source(mana)));
        return pool.size() == remaining.size() && remaining.equals(new HashSet<>(pool))
                && activatedSourcesAreTapped(answer);
    }

    private boolean activatedSourcesAreTapped(List<Integer> answer) {
        return answer.subList(2, 2 + answer.get(1)).stream().allMatch(i -> sources.get(i).getHostCard().isTapped());
    }

    void validate(List<Integer> answer) {
        if (!hasValidAnswerHeader(answer)) {
            throw new IllegalArgumentException("Invalid mana payment answer");
        }
        int split = 2 + answer.get(1);
        var activated = new HashSet<Integer>();
        var spent = new HashSet<Integer>();
        for (int j = 2; j < answer.size(); j++) {
            int i = answer.get(j);
            if (!isSourceIndex(i)) { throw new IllegalArgumentException("Invalid or unavailable payment source"); }
            if (isActivationEntry(j, split)) {
                validateActivation(i, activated);
            } else {
                validateSpend(i, spent, activated);
            }
        }
        ManaCostBeingPaid debt = new ManaCostBeingPaid(cost);
        for (int i : answer.subList(split, answer.size())) {
            SpellAbility ability = sources.get(i);
            Mana unit = new Mana(MagicColor.fromName(ability.getParam("Produced")), ability.getHostCard(), ability.getManaPart(), player);
            if (!debt.isNeeded(unit, player.getManaPool())) { throw new IllegalArgumentException("Unneeded mana unit"); }
            debt.payMana(unit, player.getManaPool());
        }
        if (!paymentResultMatchesDebt(answer, debt)) { throw new IllegalArgumentException("Payment result disagrees with native debt"); }
    }

    private static boolean hasValidAnswerHeader(List<Integer> answer) {
        return hasHeaderAndIntegerValues(answer) && hasValidPaymentFlag(answer) && hasValidActivationCount(answer);
    }

    private static boolean hasHeaderAndIntegerValues(List<Integer> answer) {
        return answer != null && answer.size() >= 2 && ((List<?>) answer).stream().allMatch(v -> v instanceof Integer);
    }

    private static boolean hasValidPaymentFlag(List<Integer> answer) {
        return answer.get(0) >= 0 && answer.get(0) <= 1;
    }

    private static boolean hasValidActivationCount(List<Integer> answer) {
        return answer.get(1) >= 0 && answer.get(1) <= answer.size() - 2;
    }

    private static boolean isActivationEntry(int position, int firstSpend) {
        return position < firstSpend;
    }

    private boolean isSourceIndex(int index) {
        return index >= 0 && index < sources.size();
    }

    private void validateActivation(int index, Set<Integer> activated) {
        boolean firstActivation = activated.add(index);
        if (!firstActivation || !available.get(index)) {
            throw new IllegalArgumentException("Invalid or unavailable payment source");
        }
    }

    private void validateSpend(int index, Set<Integer> spent, Set<Integer> activated) {
        boolean firstSpend = spent.add(index);
        if (!firstSpend || !hasAvailableMana(index, activated)) {
            throw new IllegalArgumentException("Invalid or unavailable payment source");
        }
    }

    private boolean hasAvailableMana(int index, Set<Integer> activated) {
        return initial.contains(index) || activated.contains(index);
    }

    private static boolean paymentResultMatchesDebt(List<Integer> answer, ManaCostBeingPaid debt) {
        return debt.isPaid() == (answer.get(0) == 1);
    }

    boolean isOriginalManaDebt(ManaCostBeingPaid debt) {
        return debt.toString().equals(new ManaCostBeingPaid(cost).toString());
    }

    boolean needsSourceActivation(int index) {
        return !initial.contains(index) && !root.getPayingManaAbilities().contains(sources.get(index));
    }

    static boolean isFinalManaSpend(int position, List<Integer> answer) {
        return position == answer.size() - 1;
    }

    boolean isCurrentUntappedSource(int index) {
        SpellAbility ability = sources.get(index);
        Card card = cards.get(index);
        return ability.getHostCard() == card && player.getGame().getCardState(card, null) == card
                && card.isInZone(ZoneType.Battlefield) && card.getController() == player && !card.isTapped();
    }

    boolean canPayActivationTapCost(SpellAbility ability, CostTap tap) {
        return ability.canPlay() && tap.canPay(ability, player, false);
    }

    @Override public Map<String, Object> data() { return data; }
}
