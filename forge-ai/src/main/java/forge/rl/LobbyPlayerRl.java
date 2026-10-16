package forge.rl;

import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.player.IGameEntitiesFactory;
import forge.game.player.Player;
import forge.game.player.PlayerController;

/**
 * LobbyPlayer for the RL agent.
 *
 * Mirrors LobbyPlayerAi: creates a PlayerControllerRl (which delegates all
 * non-priority decisions to the AI fallback) for the in-game player.
 */
public class LobbyPlayerRl extends LobbyPlayer implements IGameEntitiesFactory {

    private final IDecisionCallback callback;

    public LobbyPlayerRl(String name, IDecisionCallback callback) {
        super(name);
        this.callback = callback;
    }

    @Override
    public PlayerController createMindSlaveController(Player master, Player slave) {
        return new PlayerControllerRl(slave.getGame(), slave, this, callback);
    }

    @Override
    public Player createIngamePlayer(Game game, int id) {
        Player rl = new Player(getName(), game, id);
        rl.setFirstController(new PlayerControllerRl(game, rl, this, callback));
        return rl;
    }

    @Override
    public void hear(LobbyPlayer player, String message) { /* deaf */ }
}

