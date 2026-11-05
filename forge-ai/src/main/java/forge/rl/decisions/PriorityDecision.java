package forge.rl.decisions;

import forge.game.player.Player;
import forge.rl.StateReader;
import forge.rl.requests.PriorityRequest;

/** Inputs captured before preparing the native request with the recorder's reader. */
public record PriorityDecision() implements Decision<PriorityRequest> {
    @Override public PriorityRequest prepare(Player player, StateReader reader) {
        return new PriorityRequest(reader, player);
    }
}
