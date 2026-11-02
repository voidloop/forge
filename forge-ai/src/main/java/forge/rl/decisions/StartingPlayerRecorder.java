package forge.rl.decisions;

import forge.game.player.Player;
import java.util.List;

public record StartingPlayerRecorder(StartingPlayerDecision request, Player chosen) implements DecisionRecorder {
    @Override public List<Integer> encode() {
        return new SelectionRecorder<>(request.selection, List.of(chosen)).encode();
    }
}
