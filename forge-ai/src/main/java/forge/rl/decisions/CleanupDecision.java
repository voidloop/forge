package forge.rl.decisions;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.rl.StateReader;
import forge.rl.requests.CleanupRequests;
import forge.rl.requests.SelectionRequest;

public record CleanupDecision(int count) implements Decision<SelectionRequest<Card>> {
    @Override public SelectionRequest<Card> prepare(Player player, StateReader reader) {
        return CleanupRequests.create(reader, player, count);
    }
}
