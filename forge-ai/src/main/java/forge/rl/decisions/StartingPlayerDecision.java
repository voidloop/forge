package forge.rl.decisions;

import forge.game.player.Player;
import forge.rl.StateReader;
import forge.rl.requests.DecisionRequest;
import forge.rl.requests.SelectionRequest;

import java.util.List;
import java.util.Map;

public final class StartingPlayerDecision implements Decision {
    private final boolean isFirstGame;
    private StartingPlayerDecision(boolean isFirstGame) {
        this.isFirstGame = isFirstGame;
    }

    public static StartingPlayerDecision create(boolean isFirstGame) {
        return new StartingPlayerDecision(isFirstGame);
    }

    @Override public DecisionRequest prepare(Player player, StateReader reader) {
        requireOrdinaryDuel(player);
        var selection = SelectionRequest.create(reader, player, "starting_player", player.getGame().getPlayers(), "", 1,
                1, false, null, null, null);
        Map<?, ?> question = (Map<?, ?>) selection.data().get("question");
        Map<String, Object> data = Map.of("state", selection.data().get("state"), "question", Map.of("kind",
                "starting_player", "isFirstGame", isFirstGame, "items", question.get("items")));
        return DecisionRequest.create(data, answer -> selection.encodeAnswer(List.of((Player) answer)));
    }

    private static void requireOrdinaryDuel(Player player) {
        if (player.getGame().getPlayers().size() != 2 || player.getOpponents().size() != 1) {
            throw new UnsupportedOperationException("Starting player observation requires an ordinary duel");
        }
    }
}
