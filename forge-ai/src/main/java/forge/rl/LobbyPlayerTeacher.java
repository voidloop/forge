package forge.rl;

import forge.ai.LobbyPlayerAi;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.player.PlayerController;

/** Native teacher lobby; decision recording uses only the original public controller API. */
public final class LobbyPlayerTeacher extends LobbyPlayerAi {
    private final IDecisionRecordCallback callback;

    public LobbyPlayerTeacher(String name, IDecisionRecordCallback callback) {
        super(name, null);
        this.callback = java.util.Objects.requireNonNull(callback);
    }

    @Override public Player createIngamePlayer(Game game, int id) {
        Player player = new Player(getName(), game, id);
        player.setFirstController(new PlayerControllerTeacher(game, player, this, callback));
        return player;
    }

    @Override public PlayerController createMindSlaveController(Player master, Player slave) {
        throw new UnsupportedOperationException("Teacher does not record player control changes");
    }
}
