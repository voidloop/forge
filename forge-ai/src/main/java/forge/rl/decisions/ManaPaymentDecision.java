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
import forge.game.cost.PaymentDecision;
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

/** Fixed mana payment: final activation order, spend order and native result, without AI plans. */
public final class ManaPaymentDecision {
    public final Map<String, Object> data;
    private final Player player;
    private final SpellAbility root;
    private final ManaCost cost;
    private final List<SpellAbility> sources = new ArrayList<>();
    private final List<Card> cards = new ArrayList<>();
    private final List<Boolean> available = new ArrayList<>();
    private final List<Integer> initial = new ArrayList<>();

    public ManaPaymentDecision(Player player, StateReader view, ManaCost cost, CostPartMana part,
            SpellAbility root, ManaConversionMatrix matrix, boolean effect) {
        this.player = player;
        this.root = root;
        this.cost = cost;
        var game = player.getGame();
        // shortcut: ordinary fixed-cost spells and basic lands; extend with later cost families.
        Card host = root.getHostCard();
        PriorityDecision.requireSpell(host);
        if (game.getAge() != GameStage.Play || game.isGameOver() || root.getActivatingPlayer() != player
                || host.getGame() != game || host.isFaceDown() || !host.getView().canBeShownTo(player.getView())
                || !root.isSpell()
                || host.getController() != player || !(host.isInZone(ZoneType.Hand) || host.isInZone(ZoneType.Stack))
                || !host.getTriggers().isEmpty() || !host.getReplacementEffects().isEmpty()
                || root.getSubAbility() != null
                || !(root.getApi() == ApiType.PermanentCreature && !root.usesTargeting()
                || root.getApi() == ApiType.DealDamage && root.usesTargeting())
                || root.getPayCosts() == null || root.getPayCosts().getCostParts().size() != 1
                || root.getPayCosts().isMandatory()
                || !cost.equals(root.getPayCosts().getTotalMana()) || !cost.equals(part.getMana())
                || cost.isNoCost() || cost.countX() != 0 || effect
                || root.isCopied() || root.isOffering() || root.isEmerge() || root.getMayPlayOption() != null
                || root.getXManaCostPaid() != null || root.hasParam("ManaConversion")
                || !root.getPayingMana().isEmpty() || !root.getPayingManaAbilities().isEmpty()
                || !game.getCardsIn(ZoneType.Command).isEmpty() || !game.getTriggerHandler().getDelayedTriggers().isEmpty()) {
            throw new UnsupportedOperationException("Fixed basic-land payment context");
        }
        for (ManaCostShard shard : cost) {
            if (!(shard == ManaCostShard.WHITE || shard == ManaCostShard.BLUE || shard == ManaCostShard.BLACK
                    || shard == ManaCostShard.RED || shard == ManaCostShard.GREEN)) {
                throw new UnsupportedOperationException("Non-elementary mana cost");
            }
        }
        identityMatrix(player.getManaPool());
        if (matrix != null) { identityMatrix(matrix); }
        for (Card card : game.getCardsIn(ZoneType.Battlefield)) {
            if (card.isCreature() && !card.isFaceDown()) { PriorityDecision.requireSpell(card); continue; }
            if (!card.isBasicLand() || card.isSnow() || card.isFaceDown()
                    || !card.getView().canBeShownTo(player.getView())
                    || !card.getTriggers().isEmpty() || !card.getStaticAbilities().isEmpty()
                    || !card.getReplacementEffects().isEmpty() || !card.getKeywords().isEmpty()) {
                throw new UnsupportedOperationException("Basic-land payment battlefield");
            }
            if (card.getController() != player) { continue; }
            var abilities = card.getSpellAbilities().stream().filter(sa -> !sa.isLandAbility()).toList();
            if (abilities.size() != 1) { throw new UnsupportedOperationException("Multiple basic-land variants"); }
            SpellAbility ability = abilities.get(0);
            if (ability.getApi() != ApiType.Mana || !ability.isManaAbility() || !ability.isActivatedAbility()
                    || ability.getSubAbility() != null || ability.getManaPart() == null
                    || !ability.getParamOrDefault("Produced", "").matches("[WUBRG]")
                    || !ability.getParamOrDefault("Amount", "1").equals("1")
                    || ability.getPayCosts() == null || ability.getPayCosts().getCostParts().size() != 1
                    || !(ability.getPayCosts().getCostParts().get(0) instanceof CostTap)
                    || !ability.getManaPart().getManaRestrictions().isEmpty()
                    || !ability.getManaPart().getExtraManaRestriction().isEmpty()) {
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
            if (initial.contains(index) || available.get(index)) {
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
        data = Map.of("state", view.snapshot(), "options", Map.of("kind", "mana_payment",
                "source", view.reference(host), "cost", cost.toString(), "items", List.copyOf(items),
                "pool", List.copyOf(initial)));
    }

    private static void identityMatrix(ManaConversionMatrix matrix) {
        for (byte color : ManaAtom.MANATYPES) {
            if (matrix.getPossibleColorUses(color) != color || matrix.isSnowForColor()) {
                throw new UnsupportedOperationException("Mana conversion/restriction");
            }
        }
    }

    public boolean isSource(SpellAbility ability) { return sources.stream().anyMatch(bound -> bound == ability); }

    private int source(Mana mana) {
        for (int i = 0; i < sources.size(); i++) {
            SpellAbility ability = sources.get(i);
            if (mana.getSourceCard().getId() == ability.getHostCard().getId()
                    && mana.getManaAbility() == ability.getManaPart()
                    && mana.getPlayer() == player && mana.getColor() == MagicColor.fromName(ability.getParam("Produced"))) {
                return i;
            }
        }
        throw new IllegalArgumentException("Unbound mana unit");
    }

    /** [paid, activation count, ordered activation indices..., ordered spent-source indices...]. */
    public List<Integer> encode(boolean paid) {
        List<Integer> answer = new ArrayList<>(List.of(paid ? 1 : 0, root.getPayingManaAbilities().size()));
        for (SpellAbility ability : root.getPayingManaAbilities()) {
            int index = sources.indexOf(ability);
            if (index < 0 || sources.get(index) != ability) { throw new IllegalArgumentException("Unbound payment ability variant"); }
            answer.add(index);
        }
        for (Mana mana : root.getPayingMana()) { answer.add(source(mana)); }
        validate(answer);
        // Check the effective pool and taps as well as the public result; failed AI payments may refund natively.
        var remaining = new HashSet<>(initial);
        remaining.addAll(answer.subList(2, 2 + answer.get(1)));
        remaining.removeAll(answer.subList(2 + answer.get(1), answer.size()));
        List<Integer> pool = new ArrayList<>();
        player.getManaPool().forEach(mana -> pool.add(source(mana)));
        if (pool.size() != remaining.size() || !remaining.equals(new HashSet<>(pool))
                || answer.subList(2, 2 + answer.get(1)).stream().anyMatch(i -> !sources.get(i).getHostCard().isTapped())) {
            throw new IllegalStateException("Unrepresentable native payment resources");
        }
        return List.copyOf(answer);
    }

    private void validate(List<Integer> answer) {
        if (answer == null || answer.size() < 2 || ((List<?>) answer).stream().anyMatch(v -> !(v instanceof Integer))
                || answer.get(0) < 0 || answer.get(0) > 1 || answer.get(1) < 0 || answer.get(1) > answer.size() - 2) {
            throw new IllegalArgumentException("Invalid mana payment answer");
        }
        int split = 2 + answer.get(1);
        var activated = new HashSet<Integer>();
        var spent = new HashSet<Integer>();
        for (int j = 2; j < answer.size(); j++) {
            int i = answer.get(j);
            if (i < 0 || i >= sources.size() || (j < split
                    ? !activated.add(i) || !available.get(i)
                    : !spent.add(i) || !(initial.contains(i) || activated.contains(i)))) {
                throw new IllegalArgumentException("Invalid or unavailable payment source");
            }
        }
        ManaCostBeingPaid debt = new ManaCostBeingPaid(cost);
        for (int i : answer.subList(split, answer.size())) {
            SpellAbility ability = sources.get(i);
            Mana unit = new Mana(MagicColor.fromName(ability.getParam("Produced")), ability.getHostCard(), ability.getManaPart(), player);
            if (!debt.isNeeded(unit, player.getManaPool())) { throw new IllegalArgumentException("Unneeded mana unit"); }
            debt.payMana(unit, player.getManaPool());
        }
        if (debt.isPaid() != (answer.get(0) == 1)) { throw new IllegalArgumentException("Payment result disagrees with native debt"); }
    }

    public boolean apply(List<Integer> answer, ManaCostBeingPaid debt) {
        identityMatrix(player.getManaPool());
        validate(answer);
        if (!debt.toString().equals(new ManaCostBeingPaid(cost).toString())) {
            throw new UnsupportedOperationException("Adjusted mana debt");
        }
        int cursor = 2;
        int split = 2 + answer.get(1);
        for (int j = split; j < answer.size(); j++) {
            int i = answer.get(j);
            if (!initial.contains(i) && !root.getPayingManaAbilities().contains(sources.get(i))) {
                while (cursor < split) {
                    int activated = answer.get(cursor++);
                    activate(activated);
                    if (activated == i) { break; }
                }
            }
            if (j == answer.size() - 1) {
                while (cursor < split) { activate(answer.get(cursor++)); }
            }
            Mana selected = null;
            for (Mana mana : player.getManaPool()) { if (source(mana) == i) { selected = mana; break; } }
            if (selected == null) { throw new IllegalStateException("Native mana unit is unavailable"); }
            // ManaPool removes by equals, which can match another source; never remove that unit silently.
            for (Mana mana : player.getManaPool()) {
                if (mana.equals(selected)) {
                    if (mana != selected) { throw new UnsupportedOperationException("Native ManaPool cannot remove the selected equivalent source"); }
                    break;
                }
            }
            if (!player.getManaPool().tryPayCostWithMana(root, debt, selected, false)) {
                throw new IllegalStateException("Native mana spending failed");
            }
            root.getPayingMana().add(selected);
        }
        while (cursor < split) { activate(answer.get(cursor++)); }
        boolean paid = answer.get(0) == 1;
        if (debt.isPaid() != paid || !encode(paid).equals(answer)) { throw new IllegalStateException("Native payment diverged"); }
        return paid;
    }

    private void activate(int index) {
        SpellAbility ability = sources.get(index);
        Card card = cards.get(index);
        if (ability.getHostCard() != card || player.getGame().getCardState(card, null) != card || !card.isInZone(ZoneType.Battlefield)
                || card.getController() != player || card.isTapped()) {
            throw new IllegalArgumentException("Stale payment source");
        }
        ability.setActivatingPlayer(player);
        CostTap tap = (CostTap) ability.getPayCosts().getCostParts().get(0);
        if (!ability.canPlay() || !tap.canPay(ability, player, false)
                || !tap.payAsDecided(player, PaymentDecision.number(1), ability, false)) {
            throw new IllegalStateException("Native payment activation rejected");
        }
        player.getGame().getStack().addAndUnfreeze(ability);
        // A real activation retains its payer: Forge's later rollback calls ability.undo().
        root.getPayingManaAbilities().add(ability);
    }
}
