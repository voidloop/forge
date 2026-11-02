package forge.rl.decisions;

import forge.game.combat.CombatUtil;
import java.util.List;

public record AttackersExecutor(AttackersRequest request, List<Integer> answer) implements DecisionExecutor<Void> {
    @Override public Void apply() {
        request.requireContext();
        if (!request.combat.getAttackers().isEmpty()) { throw new IllegalArgumentException("Combat changed during decision"); }
        var chosen = request.validate(answer);
        for (int i = 0; i < chosen.size(); i++) {
            request.combat.addAttacker(chosen.get(i), request.defenders.get(answer.get(i * 2 + 1)));
        }
        if (!CombatUtil.validateAttackers(request.combat)) { throw new IllegalArgumentException("Native attack declaration invalid"); }
        return null;
    }
}
