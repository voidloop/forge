package forge.rl.decisions;

import forge.rl.StateReader;
import forge.game.Game;

import forge.StaticData;
import forge.MulliganDefs;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Single-hand London decisions; returned card order is native bottom insertion order. */
public final class MulliganDecision {
    static void require(Player player) {
        var game = player.getGame();
        if (isOutsideLondonDuel(game)) {
            throw new UnsupportedOperationException("Mulligan outside single-hand London duel");
        }
    }

    public static Map<String, Object> keep(Player player, StateReader view, Player first, int count) {
        require(player);
        if (hasInvalidKeepContext(player, first, count)) {
            throw new IllegalArgumentException("Invalid native mulligan context");
        }
        return Map.of("state", view.snapshot(), "options", Map.of("kind", "mulligan_keep",
                "firstPlayer", view.reference(first), "cardsToReturn", count,
                "items", List.of("mulligan", "keep")));
    }

    public static DecisionRequest<Card> tuck(Player player, StateReader view, CardCollectionView hand, int count) {
        require(player);
        if (hasInvalidTuckContext(player, hand, count)) {
            throw new UnsupportedOperationException("Invalid native London tuck count or hand");
        }
        return new DecisionRequest<>(player, view, "mulligan_tuck", hand, "", count, count,
                false, null, null, null);
    }

    private static boolean isOutsideLondonDuel(Game game) {
        return game.getAge() != GameStage.Mulligan || game.getPhaseHandler().getPhase() != null
                || game.getRules().getGameType() != GameType.Constructed
                || game.getPlayers().size() != 2
                || Arrays.stream(GameType.values()).anyMatch(game.getRules()::hasAppliedVariant)
                || StaticData.instance().getFilteredHandsEnabled()
                || StaticData.instance().getMulliganRule() != MulliganDefs.MulliganRule.London
                || game.getPlayers().stream().anyMatch(p -> !p.getCardsIn(ZoneType.Command).isEmpty());
    }

    private static boolean hasInvalidKeepContext(Player player, Player first, int count) {
        return !player.getGame().getPlayers().contains(first) || count < 0;
    }

    private static boolean hasInvalidTuckContext(Player player, CardCollectionView hand, int count) {
        return count < 0 || count > hand.size()
                || hand.size() != player.getCardsIn(ZoneType.Hand).size()
                || !player.getCardsIn(ZoneType.Hand).containsAll(hand);
    }
}
