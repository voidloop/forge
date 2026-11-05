package forge.rl.decision.context;

import forge.rl.StateReader;
import forge.game.card.CardCollectionView;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/** The native cleanup count is mandatory; the engine performs the discard. */
public final class CleanupContexts {
    public static SelectionContext<Card> create(Player player, int count) {
        return create(null, player, count);
    }

    public static SelectionContext<Card> create(StateReader reader, Player player, int count) {
        var hand = player.getCardsIn(ZoneType.Hand);
        if (player.getGame().isGameOver()) {
            throw new IllegalArgumentException("Invalid native cleanup discard context");
        }
        if (hasInvalidDiscardCount(hand, count)) {
            throw new IllegalArgumentException("Invalid native cleanup discard count");
        }
        return reader == null ? new SelectionContext<>(player, "discard_cleanup", hand, "", count, count,
                false, null, null, null)
                : SelectionContext.create(reader, player, "discard_cleanup", hand, "", count, count,
                false, null, null, null);
    }

    private static boolean hasInvalidDiscardCount(CardCollectionView hand, int count) {
        return count <= 0 || count > hand.size();
    }
}
