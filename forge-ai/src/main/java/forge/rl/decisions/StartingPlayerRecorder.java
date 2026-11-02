package forge.rl.decisions;

import forge.game.player.Player;
import java.util.List;

public record StartingPlayerRecorder() implements DecisionRecorder<StartingPlayerRequest, Player> {
    @Override public List<Integer> encode(StartingPlayerRequest request, Player chosen) {
        return new SelectionRecorder<Player>().encode(request.selection, List.of(chosen));
    }
}
