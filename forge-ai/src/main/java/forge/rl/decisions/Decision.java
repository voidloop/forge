package forge.rl.decisions;

import forge.game.player.Player;
import forge.rl.StateReader;
import forge.rl.requests.DecisionRequest;

interface Decision {
    DecisionRequest prepare(Player player, StateReader reader);
}
