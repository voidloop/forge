package forge.rl;

import forge.game.GameStage;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/** The native cleanup count is mandatory; the engine performs the discard. */
final class CleanupDecision {
    static DecisionRequest<Card> create(Player player, StateReader view, int count) {
        var game = player.getGame();
        var hand = player.getCardsIn(ZoneType.Hand);
        if (game.getAge() != GameStage.Play || game.isGameOver()
                || !game.getPhaseHandler().is(PhaseType.CLEANUP, player)
                || player.isUnlimitedHandSize() || count <= 0
                || count != hand.size() - player.getMaxHandSize() || count > hand.size()) {
            throw new IllegalArgumentException("Invalid native cleanup discard context or count");
        }
        return new DecisionRequest<>(player, view, "discard_cleanup", hand, "", count, count,
                false, null, null, null);
    }
}
