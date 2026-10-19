package forge.rl;

import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.player.IGameEntitiesFactory;
import forge.game.player.Player;
import forge.game.player.PlayerController;

/** Creates the independent agent controller for a game or controlled player. */
public class LobbyPlayerRl extends LobbyPlayer implements IGameEntitiesFactory {
    private final IPlayerDecisionCallback callback;

    public LobbyPlayerRl(String name, IPlayerDecisionCallback callback) {
        super(name);
        this.callback = java.util.Objects.requireNonNull(callback);
    }

    private PlayerController createController(Game game, Player player) {
        return new PlayerControllerRl(game, player, this, callback);
    }

    @Override
    public PlayerController createMindSlaveController(Player master, Player slave) {
        return createController(slave.getGame(), slave);
    }

    @Override
    public Player createIngamePlayer(Game game, int id) {
        Player rl = new Player(getName(), game, id);
        rl.setFirstController(createController(game, rl));
        return rl;
    }

    @Override
    public void hear(LobbyPlayer player, String message) { /* deaf */ }
}
