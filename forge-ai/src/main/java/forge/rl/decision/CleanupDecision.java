package forge.rl.decision;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.rl.StateReader;
import forge.rl.decision.context.CleanupContexts;
import forge.rl.decision.context.SelectionContext;

public record CleanupDecision(int count) implements Decision<SelectionContext<Card>> {
    @Override public SelectionContext<Card> prepare(Player player, StateReader reader) {
        return CleanupContexts.create(reader, player, count);
    }
}
