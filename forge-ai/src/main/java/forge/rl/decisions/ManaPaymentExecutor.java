package forge.rl.decisions;

import forge.game.cost.CostTap;
import forge.game.cost.PaymentDecision;
import forge.game.mana.Mana;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.spellability.SpellAbility;
import java.util.ArrayList;
import java.util.List;

public record ManaPaymentExecutor(ManaPaymentDecision request, List<Integer> answer, ManaCostBeingPaid debt) implements DecisionExecutor<Boolean> {
    @Override public Boolean apply() {
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
                    activate(activated);
                    if (activated == i) { break; }
                }
            }
            if (ManaPaymentDecision.isFinalManaSpend(j, answer)) {
                while (cursor < split) { activate(answer.get(cursor++)); }
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
        while (cursor < split) { activate(answer.get(cursor++)); }
        boolean paid = answer.get(0) == 1;
        if (!appliedPaymentMatches()) { throw new IllegalStateException("Native payment diverged"); }
        return paid;
    }

    private boolean appliedPaymentMatches() {
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

    private void activate(int index) {
        if (!request.isCurrentUntappedSource(index)) {
            throw new IllegalArgumentException("Stale payment source");
        }
        SpellAbility ability = request.sources.get(index);
        ability.setActivatingPlayer(request.player);
        if (!payActivationTapCost(ability)) {
            throw new IllegalStateException("Native payment activation rejected");
        }
        request.player.getGame().getStack().addAndUnfreeze(ability);
        // A real activation retains its payer: Forge's later rollback calls ability.undo().
        request.root.getPayingManaAbilities().add(ability);
    }

    private boolean payActivationTapCost(SpellAbility ability) {
        CostTap tap = (CostTap) ability.getPayCosts().getCostParts().get(0);
        return request.canPayActivationTapCost(ability, tap)
                && tap.payAsDecided(request.player, PaymentDecision.number(1), ability, false);
    }
}
