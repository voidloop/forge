package forge.rl.decisions;

import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.combat.CombatUtil;
import java.util.ArrayList;
import java.util.List;

public record AttackersRecorder() implements DecisionRecorder<AttackersQuestion, Void> {
    @Override public List<Integer> encode(AttackersQuestion request, Void ignored) {
        request.requireContext();
        var answer = new ArrayList<Integer>();
        for (Card card : request.combat.getAttackers()) {
            GameEntity defender = request.combat.getDefenderByAttacker(card);
            int a = request.attackers.indexOf(card), d = request.defenders.indexOf(defender);
            if (!hasExactBindings(request, card, defender, a, d)) {
                throw new IllegalArgumentException("Final attack declaration outside exact bindings");
            }
            answer.add(a);
            answer.add(d);
        }
        request.validate(answer);
        if (!CombatUtil.validateAttackers(request.combat)) { throw new IllegalArgumentException("Native attack declaration invalid"); }
        return List.copyOf(answer);
    }

    private boolean hasExactBindings(AttackersQuestion request, Card attacker, GameEntity defender, int attackerIndex, int defenderIndex) {
        return attackerIndex >= 0 && request.attackers.get(attackerIndex) == attacker
                && defenderIndex >= 0 && request.defenders.get(defenderIndex) == defender;
    }
}
