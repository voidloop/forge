package forge.rl.decision;

import forge.game.player.Player;
import forge.rl.StateReader;
import forge.rl.decision.context.ManaContext;

/** Inputs captured before preparing the native request with the recorder's reader. */
public record ManaDecision() implements Decision<ManaContext> {
    @Override public ManaContext prepare(Player player, StateReader reader) {
        return new ManaContext(reader, player);
    }
}
