package forge.rl.requests;

import forge.rl.StateReader;
import forge.game.card.CardCollectionView;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/** The native cleanup count is mandatory; the engine performs the discard. */
public final class CleanupRequests {
    public static SelectionRequest<Card> create(Player player, int count) {
        return create(null, player, count);
    }

    public static SelectionRequest<Card> create(StateReader reader, Player player, int count) {
        var hand = player.getCardsIn(ZoneType.Hand);
        if (player.getGame().isGameOver()) {
            throw new IllegalArgumentException("Invalid native cleanup discard context");
        }
        if (hasInvalidDiscardCount(hand, count)) {
            throw new IllegalArgumentException("Invalid native cleanup discard count");
        }
        return reader == null ? new SelectionRequest<>(player, "discard_cleanup", hand, "", count, count,
                false, null, null, null)
                : SelectionRequest.create(reader, player, "discard_cleanup", hand, "", count, count,
                false, null, null, null);
    }

    private static boolean hasInvalidDiscardCount(CardCollectionView hand, int count) {
        return count <= 0 || count > hand.size();
    }
}
