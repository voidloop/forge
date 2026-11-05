package forge.rl.decision;

import forge.game.player.Player;
import forge.rl.StateReader;
import forge.rl.decision.context.PriorityContext;

/** Inputs captured before preparing the native request with the recorder's reader. */
public record PriorityDecision() implements Decision<PriorityContext> {
    @Override public PriorityContext prepare(Player player, StateReader reader) {
        return new PriorityContext(reader, player);
    }
}
