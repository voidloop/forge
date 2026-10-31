package forge.rl;

import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.player.IGameEntitiesFactory;
import forge.game.player.Player;
import forge.game.player.PlayerController;

/** Creates an independent controller bound to one external client. */
public final class LobbyPlayerRl extends LobbyPlayer implements IGameEntitiesFactory {
    private final IPlayerDecisionCallback callback;

    public LobbyPlayerRl(String name, IPlayerDecisionCallback callback) {
        super(name);
        this.callback = java.util.Objects.requireNonNull(callback);
    }

    @Override public Player createIngamePlayer(Game game, int id) {
        Player player = new Player(getName(), game, id);
        player.setFirstController(new PlayerControllerRl(game, player, this, callback));
        return player;
    }

    @Override public PlayerController createMindSlaveController(Player master, Player slave) {
        throw new UnsupportedOperationException("PlayerControllerRl: player control changes are not implemented");
    }

    @Override public void hear(LobbyPlayer player, String message) { }
}
