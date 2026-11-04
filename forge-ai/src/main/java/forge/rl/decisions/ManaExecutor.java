package forge.rl.decisions;

import forge.game.player.Player;
import java.util.List;

public record ManaExecutor(ManaRequest request, List<Integer> answer, Player player) implements DecisionExecutor<ManaRequest.Choice> {
    @Override public ManaRequest.Choice apply() {
        int index = ManaRequest.index(answer, request.choice == null ? 1 : 2);
        if (index == 0) { return null; }
        request.choice.ability.setActivatingPlayer(player);
        return request.choice;
    }
}
