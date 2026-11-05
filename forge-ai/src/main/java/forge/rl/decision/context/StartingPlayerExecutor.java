package forge.rl.decision.context;

import forge.game.player.Player;
import java.util.List;

public record StartingPlayerExecutor(StartingPlayerContext request, List<Integer> answer) implements DecisionExecutor<Player> {
    @Override public Player apply() {
        return new SelectionExecutor<>(request.selection, answer).apply().get(0);
    }
}
