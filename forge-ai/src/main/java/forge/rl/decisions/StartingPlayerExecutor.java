package forge.rl.decisions;

import forge.game.player.Player;
import java.util.List;

public record StartingPlayerExecutor(StartingPlayerRequest request, List<Integer> answer) implements DecisionExecutor<Player> {
    @Override public Player apply() {
        return new SelectionExecutor<>(request.selection, answer).apply().get(0);
    }
}
