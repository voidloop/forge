package forge.rl.decisions;

import forge.game.player.Player;
import forge.rl.StateReader;
import forge.rl.requests.ManaRequest;

/** Inputs captured before preparing the native request with the recorder's reader. */
public record ManaDecision() implements Decision<ManaRequest> {
    @Override public ManaRequest prepare(Player player, StateReader reader) {
        return new ManaRequest(reader, player);
    }
}
