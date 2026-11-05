package forge.rl.decisions;

import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.rl.StateReader;
import forge.rl.requests.DecisionRequest;

interface Decision<R extends DecisionRequest> {
    R prepare(Player player, StateReader reader);

    /** Exact ability for the recorder's nested-selection scope, when applicable. */
    default SpellAbility ability() { return null; }

    default void beforeOriginal(R request) { }

    default void afterOriginal(R request, Object answer) { }
}
