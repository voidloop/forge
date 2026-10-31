package forge.rl;

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
final class MulliganDecision {
    static void require(Player player) {
        var game = player.getGame();
        if (game.getAge() != GameStage.Mulligan || game.getPhaseHandler().getPhase() != null
                || game.getRules().getGameType() != GameType.Constructed
                || game.getPlayers().size() != 2
                || Arrays.stream(GameType.values()).anyMatch(game.getRules()::hasAppliedVariant)
                || StaticData.instance().getFilteredHandsEnabled()
                || StaticData.instance().getMulliganRule() != MulliganDefs.MulliganRule.London
                || game.getPlayers().stream().anyMatch(p -> !p.getCardsIn(ZoneType.Command).isEmpty())) {
            throw new UnsupportedOperationException("Mulligan outside single-hand London duel");
        }
    }

    static Map<String, Object> keep(Player player, StateReader view, Player first, int count) {
        require(player);
        if (!player.getGame().getPlayers().contains(first) || count < 0) {
            throw new IllegalArgumentException("Invalid native mulligan context");
        }
        return Map.of("state", view.snapshot(), "options", Map.of("kind", "mulligan_keep",
                "firstPlayer", view.reference(first), "cardsToReturn", count,
                "items", List.of("mulligan", "keep")));
    }

    static boolean apply(List<Integer> answer) {
        if (answer.size() != 1 || answer.get(0) < 0 || answer.get(0) > 1) {
            throw new IllegalArgumentException("Invalid keep response");
        }
        return answer.get(0) == 1;
    }

    static DecisionRequest<Card> tuck(Player player, StateReader view, CardCollectionView hand, int count) {
        require(player);
        if (count < 0 || count > hand.size()
                || hand.size() != player.getCardsIn(ZoneType.Hand).size()
                || !player.getCardsIn(ZoneType.Hand).containsAll(hand)) {
            throw new UnsupportedOperationException("Invalid native London tuck count or hand");
        }
        return new DecisionRequest<>(player, view, "mulligan_tuck", hand, "", count, count,
                false, null, null, null);
    }
}
