package forge.rl.decision.context;

import forge.game.player.Player;
import java.util.List;

public record ManaExecutor(ManaContext request, List<Integer> answer, Player player) implements DecisionExecutor<ManaContext.Choice> {
    @Override public ManaContext.Choice apply() {
        int index = ManaContext.index(answer, request.choice == null ? 1 : 2);
        if (index == 0) { return null; }
        request.choice.ability.setActivatingPlayer(player);
        return request.choice;
    }
}
