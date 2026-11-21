package forge.rl;

import forge.ai.LobbyPlayerAi;
import forge.game.Game;
import forge.game.player.Player;
import java.util.Objects;

/** Creates the dataset-producing original-AI controller, without an inference callback. */
public final class LobbyPlayerTeacher extends LobbyPlayerAi {
    private final ITeacherRecorder sink;

    public LobbyPlayerTeacher(String name, ITeacherRecorder sink) {
        super(name, null);
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    @Override
    public Player createIngamePlayer(Game game, int id) {
        Player player = new Player(getName(), game, id);
        player.setFirstController(new PlayerControllerTeacher(game, player, this, sink));
        return player;
    }
}
