package forge.rl.decision;

import forge.card.ColorSet;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.rl.StateReader;
import forge.rl.decision.context.ManaContext;

public record ColorDecision(ManaContext.Choice choice, String message, SpellAbility source, ColorSet offered)
        implements Decision<ManaContext.ColorContext> {
    @Override public ManaContext.ColorContext prepare(Player player, StateReader reader) {
        return choice.colorRequest(reader, player, message, source, offered);
    }
}
