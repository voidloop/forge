package forge.rl.decision.context;

import forge.game.player.Player;

import java.util.List;
import java.util.Map;

/** The native starting-player choice; observation currently supports ordinary duels. */
public final class StartingPlayerContext implements DecisionContext {
    private static final String KIND = "starting_player";

    public final Map<String, Object> data;
    final SelectionContext<Player> selection;

    public StartingPlayerContext(Player player, boolean isFirstGame) {
        var game = player.getGame();
        if (!isOrdinaryDuel(player)) {
            throw new UnsupportedOperationException("Starting player observation requires an ordinary duel");
        }
        selection = new SelectionContext<>(player, KIND, game.getPlayers(), "", 1, 1,
                false, null, null, null);
        data = Map.of("state", selection.encodeQuestion().get("state"), "question", Map.of("kind", KIND,
                "isFirstGame", isFirstGame, "items", ((Map<?, ?>) selection.encodeQuestion().get("question")).get("items")));
    }

    @Override public List<Integer> encodeAnswer(Object answer) {
        return selection.encodeAnswer(List.of((Player) answer));
    }

    @Override public String kind() { return KIND; }

    @Override public Map<String, Object> encodeQuestion() { return data; }

    private static boolean isOrdinaryDuel(Player player) {
        return player.getGame().getPlayers().size() == 2 && player.getOpponents().size() == 1;
    }
}
