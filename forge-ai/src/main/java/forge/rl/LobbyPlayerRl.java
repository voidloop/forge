package forge.rl;

import forge.ai.LobbyPlayerAi;
import forge.game.Game;
import forge.game.player.Player;

public final class LobbyPlayerRl extends LobbyPlayerAi {
    private final IPriorityChoices choices;

    public LobbyPlayerRl(String name, IPriorityChoices choices) {
        super(name, null);
        this.choices = choices;
    }

    @Override
    public Player createIngamePlayer(Game game, int id) {
        Player player = new Player(getName(), game, id);
        player.setFirstController(new PlayerControllerRl(game, player, this, choices));
        return player;
    }
}
