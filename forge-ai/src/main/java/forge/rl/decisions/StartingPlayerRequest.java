package forge.rl.decisions;

import forge.rl.StateReader;
import forge.game.player.Player;

import java.util.Map;

/** The native starting-player choice; observation currently supports ordinary duels. */
public final class StartingPlayerRequest implements DecisionRequest {
    public final Map<String, Object> data;
    final SelectionRequest<Player> selection;

    public StartingPlayerRequest(Player player, StateReader view, boolean isFirstGame) {
        var game = player.getGame();
        if (!isOrdinaryDuel(player)) {
            throw new UnsupportedOperationException("Starting player observation requires an ordinary duel");
        }
        selection = new SelectionRequest<>(player, view, "starting_player", game.getPlayers(), "", 1, 1,
                false, null, null, null);
        data = Map.of("state", selection.data.get("state"), "options", Map.of("kind", "starting_player",
                "isFirstGame", isFirstGame, "items", ((Map<?, ?>) selection.data.get("options")).get("items")));
    }

    @Override public Map<String, Object> data() { return data; }

    private static boolean isOrdinaryDuel(Player player) {
        return player.getGame().getPlayers().size() == 2 && player.getOpponents().size() == 1;
    }
}
