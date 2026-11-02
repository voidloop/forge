package forge.rl.decisions;

import forge.rl.StateReader;
import forge.game.card.CardCollectionView;
import forge.game.Game;

import forge.game.GameStage;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/** The native cleanup count is mandatory; the engine performs the discard. */
public final class CleanupRequests {
    public static SelectionRequest<Card> create(Player player, StateReader view, int count) {
        var game = player.getGame();
        var hand = player.getCardsIn(ZoneType.Hand);
        if (hasInvalidCleanupContext(game, player, hand, count)) {
            throw new IllegalArgumentException("Invalid native cleanup discard context or count");
        }
        return new SelectionRequest<>(player, view, "discard_cleanup", hand, "", count, count,
                false, null, null, null);
    }

    private static boolean hasInvalidCleanupContext(Game game, Player player, CardCollectionView hand, int count) {
        return game.getAge() != GameStage.Play || game.isGameOver()
                || !game.getPhaseHandler().is(PhaseType.CLEANUP, player)
                || player.isUnlimitedHandSize() || count <= 0
                || count != hand.size() - player.getMaxHandSize() || count > hand.size();
    }
}
