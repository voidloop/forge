package forge.rl.decision;

import forge.game.player.Player;
import forge.game.combat.Combat;
import forge.rl.StateReader;
import forge.rl.decision.context.AttackersContext;

/** Inputs captured before preparing the native request with the recorder's reader. */
public record AttackersDecision(Player attacker, Combat combat) implements Decision<AttackersContext> {
    @Override public AttackersContext prepare(Player player, StateReader reader) {
        return new AttackersContext(reader, player, attacker, combat);
    }
}
