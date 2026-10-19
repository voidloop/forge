package forge.rl;

import forge.game.player.Player;
import java.util.List;

/** The agent answers a native player decision; indices refer only to the offered list. */
public interface IPlayerDecisionCallback {
    int chooseStartingPlayer(Player actor, String prompt, List<Player> options);
}
