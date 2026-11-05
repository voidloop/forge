package forge.rl.decision;

import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.rl.StateReader;
import forge.rl.decision.context.DecisionContext;

public interface Decision<C extends DecisionContext> {
    C prepare(Player player, StateReader reader);

    /** Exact ability for the recorder's nested-selection scope, when applicable. */
    default SpellAbility ability() { return null; }

}
