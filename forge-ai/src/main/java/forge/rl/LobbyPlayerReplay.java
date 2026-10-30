package forge.rl;

import forge.ai.LobbyPlayerAi;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.player.PlayerController;

/** Used only by the explicitly restricted creature replay fixture. */
public final class LobbyPlayerReplay extends LobbyPlayerAi {
    private final IGameReplayCallback callback;
    public LobbyPlayerReplay(String name, IGameReplayCallback callback) {
        super(name, null);
        this.callback = callback;
    }
    @Override public Player createIngamePlayer(Game game, int id) {
        Player player = new Player(getName(), game, id);
        player.setFirstController(new PlayerControllerReplay(game, player, this, callback));
        return player;
    }
    @Override public PlayerController createMindSlaveController(Player master, Player slave) {
        throw new UnsupportedOperationException("Control changes are outside the replay fixture");
    }
}
