package forge.rl.decisions;

import forge.rl.StateReader;
import forge.game.card.CardCollectionView;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/** The native cleanup count is mandatory; the engine performs the discard. */
public final class CleanupQuestions {
    public static SelectionQuestion<Card> create(Player player, StateReader view, int count) {
        var hand = player.getCardsIn(ZoneType.Hand);
        if (player.getGame().isGameOver()) {
            throw new IllegalArgumentException("Invalid native cleanup discard context");
        }
        if (hasInvalidDiscardCount(hand, count)) {
            throw new IllegalArgumentException("Invalid native cleanup discard count");
        }
        return new SelectionQuestion<>(player, view, "discard_cleanup", hand, "", count, count,
                false, null, null, null);
    }

    private static boolean hasInvalidDiscardCount(CardCollectionView hand, int count) {
        return count <= 0 || count > hand.size();
    }
}
