package forge.rl.decisions;

import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.combat.CombatUtil;
import forge.game.cost.CostTap;
import forge.game.cost.PaymentDecision;
import forge.game.mana.Mana;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.*;

/** Applies client answers for PlayerControllerRl; never records teacher choices. */
public final class DecisionExecutor {
    private DecisionExecutor() { }

    public static <T extends GameEntity> List<T> apply(DecisionRequest<T> request, List<Integer> selected) {
        request.validate(selected);
        return selected.stream().map(request.bindings::get).toList();
    }

    public static Player apply(StartingPlayerDecision request, List<Integer> answer) { return apply(request.selection, answer).get(0); }

    public static boolean applyKeep(List<Integer> answer) {
        if (answer.size() != 1 || answer.get(0) < 0 || answer.get(0) > 1) {
            throw new IllegalArgumentException("Invalid keep response");
        }
        return answer.get(0) == 1;
    }

    public static void apply(AttackersDecision request, List<Integer> answer) {
        request.requireContext();
        if (!request.combat.getAttackers().isEmpty()) { throw new IllegalArgumentException("Combat changed during decision"); }
        var chosen = request.validate(answer);
        for (int i = 0; i < chosen.size(); i++) {
            request.combat.addAttacker(chosen.get(i), request.defenders.get(answer.get(i * 2 + 1)));
        }
        if (!CombatUtil.validateAttackers(request.combat)) { throw new IllegalArgumentException("Native attack declaration invalid"); }
    }

    public static void apply(BlockersDecision request, List<Integer> answer) {
        request.requireContext(); request.requireAttackers();
        if (!request.combat.getAllBlockers().isEmpty()) { throw new IllegalArgumentException("Combat changed during decision"); }
        request.validate(answer);
        for (int i = 0; i < answer.size(); i += 2) {
            Card attacker = request.attackers.get(answer.get(i + 1)), blocker = request.blockers.get(answer.get(i));
            if (!CombatUtil.canBlock(attacker, blocker, request.combat)) { throw new IllegalArgumentException("Native block pair invalid"); }
            request.combat.addBlocker(attacker, blocker);
        }
        request.validateNative();
    }

    public static Map<Card, Integer> apply(CombatDamageDecision request, List<Integer> answer) {
        request.validate(answer);
        Map<Card, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < answer.size(); i += 2) {
            result.put(request.targets.get(answer.get(i)), answer.get(i + 1));
        }
        return result;
    }

    public static CardCollection apply(CombatGraveyardDecision request, List<Integer> answer) {
        request.requireContext();
        return new CardCollection(apply(request.selection, answer));
    }

    public static PriorityDecision.Choice apply(PriorityDecision request, List<Integer> answer) {
        PriorityDecision.Choice choice = request.validate(answer);
        if (choice == null) { return null; }
        choice.bindTarget(answer);
        choice.ability.setActivatingPlayer(request.player);
        return choice;
    }

    public static boolean applyTarget(PriorityDecision.Choice request, Player player, SpellAbility selected) {
        if (selected != request.ability || request.targets == null || !request.ability.getTargets().isEmpty()) {
            throw new IllegalArgumentException("Unbound ordinary target request");
        }
        if (request.targetIndex == -1) { return false; }
        GameEntity target = request.targets.get(request.targetIndex);
        if (target instanceof Card c && player.getGame().getCardState(c, null) != c || !request.ability.canTarget(target)) {
            throw new IllegalArgumentException("Selected target is no longer legal");
        }
        request.ability.getTargets().add(target);
        return true;
    }

    public static ManaDecision.Choice apply(ManaDecision request, List<Integer> answer, Player player) {
        int index = ManaDecision.index(answer, request.choice == null ? 1 : 2);
        if (index == 0) { return null; }
        request.choice.ability.setActivatingPlayer(player);
        return request.choice;
    }

    public static byte applyColor(ManaDecision.Choice request, List<Integer> answer) {
        var options = request.colors().stream().toList();
        request.color = options.get(ManaDecision.index(answer, options.size())).getColorMask();
        return request.color;
    }

    public static void apply(TriggerDecision request, List<Integer> answer) {
        if (!request.isPermutation(answer)) { throw new IllegalArgumentException("Mandatory ETB preparation answer"); }
        request.requireLiveSources();
        if (!TriggerDecision.sameAbilities(TriggerDecision.stack(request.player), request.stackBefore)) {
            throw new IllegalArgumentException("Stale ETB preparation");
        }
        for (int index : answer) { request.player.getGame().getStack().add(request.bindings.get(index).wrapper()); }
        request.preparedStack();
    }

    public static boolean apply(ManaPaymentDecision request, List<Integer> answer, ManaCostBeingPaid debt) {
        ManaPaymentDecision.identityMatrix(request.player.getManaPool());
        request.validate(answer);
        if (!request.isOriginalManaDebt(debt)) {
            throw new UnsupportedOperationException("Adjusted mana debt");
        }
        int cursor = 2;
        int split = 2 + answer.get(1);
        for (int j = split; j < answer.size(); j++) {
            int i = answer.get(j);
            if (request.needsSourceActivation(i)) {
                while (cursor < split) {
                    int activated = answer.get(cursor++);
                    activate(request, activated);
                    if (activated == i) { break; }
                }
            }
            if (ManaPaymentDecision.isFinalManaSpend(j, answer)) {
                while (cursor < split) { activate(request, answer.get(cursor++)); }
            }
            Mana selected = null;
            for (Mana mana : request.player.getManaPool()) { if (request.source(mana) == i) { selected = mana; break; } }
            if (selected == null) { throw new IllegalStateException("Native mana unit is unavailable"); }
            // ManaPool removes by equals, which can match another source; never remove that unit silently.
            for (Mana mana : request.player.getManaPool()) {
                if (mana.equals(selected)) {
                    if (mana != selected) { throw new UnsupportedOperationException("Native ManaPool cannot remove the selected equivalent source"); }
                    break;
                }
            }
            if (!request.player.getManaPool().tryPayCostWithMana(request.root, debt, selected, false)) {
                throw new IllegalStateException("Native mana spending failed");
            }
            request.root.getPayingMana().add(selected);
        }
        while (cursor < split) { activate(request, answer.get(cursor++)); }
        boolean paid = answer.get(0) == 1;
        if (!appliedPaymentMatches(request, answer, debt)) { throw new IllegalStateException("Native payment diverged"); }
        return paid;
    }

    private static boolean appliedPaymentMatches(ManaPaymentDecision request, List<Integer> answer, ManaCostBeingPaid debt) {
        boolean paid = answer.get(0) == 1;
        if (debt.isPaid() != paid) { return false; }
        // Validate effective operations independently of the teacher's recording API.
        List<Integer> actual = new ArrayList<>(List.of(paid ? 1 : 0, request.root.getPayingManaAbilities().size()));
        for (SpellAbility ability : request.root.getPayingManaAbilities()) {
            int index = request.sources.indexOf(ability);
            if (!request.isBoundSourceAbility(index, ability)) { throw new IllegalArgumentException("Unbound payment ability variant"); }
            actual.add(index);
        }
        for (Mana mana : request.root.getPayingMana()) { actual.add(request.source(mana)); }
        request.validate(actual);
        if (!request.hasExpectedPaymentResources(actual)) {
            throw new IllegalStateException("Unrepresentable native payment resources");
        }
        return actual.equals(answer);
    }

    private static void activate(ManaPaymentDecision request, int index) {
        if (!request.isCurrentUntappedSource(index)) {
            throw new IllegalArgumentException("Stale payment source");
        }
        SpellAbility ability = request.sources.get(index);
        ability.setActivatingPlayer(request.player);
        if (!payActivationTapCost(request, ability)) {
            throw new IllegalStateException("Native payment activation rejected");
        }
        request.player.getGame().getStack().addAndUnfreeze(ability);
        // A real activation retains its payer: Forge's later rollback calls ability.undo().
        request.root.getPayingManaAbilities().add(ability);
    }

    private static boolean payActivationTapCost(ManaPaymentDecision request, SpellAbility ability) {
        CostTap tap = (CostTap) ability.getPayCosts().getCostParts().get(0);
        return request.canPayActivationTapCost(ability, tap)
                && tap.payAsDecided(request.player, PaymentDecision.number(1), ability, false);
    }
}
