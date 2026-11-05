package forge.rl.decisions;

import forge.card.ColorSet;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.rl.StateReader;
import forge.rl.requests.ManaRequest;

public record ColorDecision(ManaRequest.Choice choice, String message, SpellAbility source, ColorSet offered)
        implements Decision<ManaRequest.ColorRequest> {
    @Override public ManaRequest.ColorRequest prepare(Player player, StateReader reader) {
        return choice.colorRequest(reader, player, message, source, offered);
    }
}
