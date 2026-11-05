package forge.rl.decisions;

import forge.game.player.Player;
import forge.rl.StateReader;
import forge.game.spellability.SpellAbility;
import java.util.List;
import forge.rl.requests.TriggerRequest;

/** Inputs captured before preparing the native request with the recorder's reader. */
public record TriggerDecision(List<SpellAbility> abilities) implements Decision<TriggerRequest> {
    @Override public TriggerRequest prepare(Player player, StateReader reader) {
        return new TriggerRequest(reader, player, abilities);
    }
}
