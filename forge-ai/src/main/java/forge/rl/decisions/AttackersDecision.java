package forge.rl.decisions;

import forge.game.player.Player;
import forge.game.combat.Combat;
import forge.rl.StateReader;
import forge.rl.requests.AttackersRequest;

/** Inputs captured before preparing the native request with the recorder's reader. */
public record AttackersDecision(Player attacker, Combat combat) implements Decision<AttackersRequest> {
    @Override public AttackersRequest prepare(Player player, StateReader reader) {
        return new AttackersRequest(reader, player, attacker, combat);
    }
}
