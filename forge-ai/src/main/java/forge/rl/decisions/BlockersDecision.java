package forge.rl.decisions;

import forge.game.player.Player;
import forge.game.combat.Combat;
import forge.rl.StateReader;
import forge.rl.requests.BlockersRequest;

/** Inputs captured before preparing the native request with the recorder's reader. */
public record BlockersDecision(Player defender, Combat combat) implements Decision<BlockersRequest> {
    @Override public BlockersRequest prepare(Player player, StateReader reader) {
        return new BlockersRequest(reader, player, defender, combat);
    }
}
