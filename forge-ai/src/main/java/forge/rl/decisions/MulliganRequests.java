package forge.rl.decisions;

import forge.rl.StateReader;
import forge.StaticData;
import forge.MulliganDefs;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import java.util.List;
import java.util.Map;

/** Single-hand London decisions; returned card order is native bottom insertion order. */
public final class MulliganRequests {
    static void require(Player player) {
        if (isOutsideLondonDuel(player)) {
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

    public static SelectionRequest<Card> tuck(Player player, StateReader view, CardCollectionView hand, int count) {
        require(player);
        if (hasInvalidTuckContext(player, hand, count)) {
            throw new UnsupportedOperationException("Invalid native London tuck count or hand");
        }
        return new SelectionRequest<>(player, view, "mulligan_tuck", hand, "", count, count,
                false, null, null, null);
    }

    private static boolean isOutsideLondonDuel(Player player) {
        return player.getGame().getPlayers().size() != 2 || player.getOpponents().size() != 1
                || StaticData.instance().getFilteredHandsEnabled()
                || StaticData.instance().getMulliganRule() != MulliganDefs.MulliganRule.London;
    }

    private static boolean hasInvalidKeepContext(Player player, Player first, int count) {
        return player.getGame().getPlayers().stream().noneMatch(candidate -> candidate == first) || count < 0;
    }

    private static boolean hasInvalidTuckContext(Player player, CardCollectionView hand, int count) {
        return count < 0 || count > hand.size()
                || hand.size() != player.getCardsIn(ZoneType.Hand).size()
                || hand.stream().anyMatch(card -> !isCurrentHandCard(player, card));
    }

    private static boolean isCurrentHandCard(Player player, Card card) {
        return player.getCardsIn(ZoneType.Hand).stream().anyMatch(current -> current == card);
    }
}
