package forge.rl.decision;

import forge.game.player.Player;
import forge.game.combat.Combat;
import forge.rl.StateReader;
import forge.rl.decision.context.BlockersContext;

/** Inputs captured before preparing the native request with the recorder's reader. */
public record BlockersDecision(Player defender, Combat combat) implements Decision<BlockersContext> {
    @Override public BlockersContext prepare(Player player, StateReader reader) {
        return new BlockersContext(reader, player, defender, combat);
    }
}
