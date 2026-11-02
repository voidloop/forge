package forge.rl.decisions;

import forge.game.player.Player;
import java.util.List;

public record ManaExecutor(ManaDecision request, List<Integer> answer, Player player) implements DecisionExecutor<ManaDecision.Choice> {
    @Override public ManaDecision.Choice apply() {
        int index = ManaDecision.index(answer, request.choice == null ? 1 : 2);
        if (index == 0) { return null; }
        request.choice.ability.setActivatingPlayer(player);
        return request.choice;
    }
}
