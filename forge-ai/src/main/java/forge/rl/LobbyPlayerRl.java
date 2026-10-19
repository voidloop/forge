package forge.rl;

import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.player.IGameEntitiesFactory;
import forge.game.player.Player;
import forge.game.player.PlayerController;

/**
 * LobbyPlayer for the RL agent.
 *
 * The decision callback creates the independent agent controller. The legacy
 * action callback retains the M9 controller until its session is replaced.
 */
public class LobbyPlayerRl extends LobbyPlayer implements IGameEntitiesFactory {

    private final IDecisionCallback callback;
    private final IPlayerDecisionCallback decisionCallback;

    public LobbyPlayerRl(String name, IDecisionCallback callback) {
        super(name);
        this.callback = callback;
        this.decisionCallback = null;
    }

    public LobbyPlayerRl(String name, IPlayerDecisionCallback callback) {
        super(name);
        this.callback = null;
        this.decisionCallback = java.util.Objects.requireNonNull(callback);
    }

    private PlayerController createController(Game game, Player player) {
        return decisionCallback != null
                ? new PlayerControllerRl(game, player, this, decisionCallback)
                : new PlayerControllerM9(game, player, this, callback);
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
