package forge.rl.decision;

import forge.game.player.Player;
import forge.rl.StateReader;
import forge.game.spellability.SpellAbility;
import java.util.List;
import forge.rl.decision.context.TriggerContext;

/** Inputs captured before preparing the native request with the recorder's reader. */
public record TriggerDecision(List<SpellAbility> abilities) implements Decision<TriggerContext> {
    @Override public TriggerContext prepare(Player player, StateReader reader) {
        return new TriggerContext(reader, player, abilities);
    }
}
