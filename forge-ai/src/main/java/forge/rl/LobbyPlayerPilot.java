package forge.rl;

import forge.ai.LobbyPlayerAi;
import forge.game.Game;
import forge.game.player.Player;

public final class LobbyPlayerPilot extends LobbyPlayerAi {
    private final IPilotChoices choices;

    public LobbyPlayerPilot(String name, IPilotChoices choices) {
        super(name, null);
        this.choices = choices;
    }

    @Override
    public Player createIngamePlayer(Game game, int id) {
        Player player = new Player(getName(), game, id);
        player.setFirstController(new PlayerControllerPilot(game, player, this, choices));
        return player;
    }
}
