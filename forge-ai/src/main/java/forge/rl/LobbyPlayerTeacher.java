package forge.rl;

import forge.ai.LobbyPlayerAi;
import forge.game.Game;
import forge.game.player.Player;

import java.util.Objects;

public final class LobbyPlayerTeacher extends LobbyPlayerAi {
    private final IDecisionRecordCallback callback;

    public LobbyPlayerTeacher(String name, IDecisionRecordCallback callback) {
        super(name, null);
        this.callback = Objects.requireNonNull(callback);
    }

    @Override public Player createIngamePlayer(Game game, int id) {
        Player ai = new Player(getName(), game, id);
        ai.setFirstController(new PlayerControllerTeacher(game, ai, this, callback));
        return ai;
    }
}
