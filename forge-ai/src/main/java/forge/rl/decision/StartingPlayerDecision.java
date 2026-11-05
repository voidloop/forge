package forge.rl.decision;

import forge.game.player.Player;
import forge.rl.StateReader;
import forge.rl.decision.context.DecisionContext;
import forge.rl.decision.context.SelectionContext;

import java.util.List;
import java.util.Map;

public final class StartingPlayerDecision implements Decision<DecisionContext> {
    private final boolean isFirstGame;
    private StartingPlayerDecision(boolean isFirstGame) {
        this.isFirstGame = isFirstGame;
    }

    public static StartingPlayerDecision create(boolean isFirstGame) {
        return new StartingPlayerDecision(isFirstGame);
    }

    @Override public DecisionContext prepare(Player player, StateReader reader) {
        requireOrdinaryDuel(player);
        var selection = SelectionContext.create(reader, player, "starting_player", player.getGame().getPlayers(), "", 1,
                1, false, null, null, null);
        Map<?, ?> question = (Map<?, ?>) selection.encodeQuestion().get("question");
        String kind = "starting_player";
        Map<String, Object> data = Map.of("state", selection.encodeQuestion().get("state"), "question", Map.of("kind",
                kind, "isFirstGame", isFirstGame, "items", question.get("items")));
        return DecisionContext.create(kind, data, answer -> selection.encodeAnswer(List.of((Player) answer)));
    }

    private static void requireOrdinaryDuel(Player player) {
        if (player.getGame().getPlayers().size() != 2 || player.getOpponents().size() != 1) {
            throw new UnsupportedOperationException("Starting player observation requires an ordinary duel");
        }
    }
}
