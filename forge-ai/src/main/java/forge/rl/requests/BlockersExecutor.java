package forge.rl.requests;

import forge.game.card.Card;
import forge.game.combat.CombatUtil;
import java.util.List;

public record BlockersExecutor(BlockersRequest request, List<Integer> answer) implements DecisionExecutor<Void> {
    @Override public Void apply() {
        request.requireContext(); request.requireAttackers();
        if (!request.combat.getAllBlockers().isEmpty()) { throw new IllegalArgumentException("Combat changed during decision"); }
        request.validate(answer);
        for (int i = 0; i < answer.size(); i += 2) {
            Card attacker = request.attackers.get(answer.get(i + 1)), blocker = request.blockers.get(answer.get(i));
            if (!CombatUtil.canBlock(attacker, blocker, request.combat)) { throw new IllegalArgumentException("Native block pair invalid"); }
            request.combat.addBlocker(attacker, blocker);
        }
        request.validateNative();
        return null;
    }
}
