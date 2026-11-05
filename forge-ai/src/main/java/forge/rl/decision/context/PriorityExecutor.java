package forge.rl.decision.context;

import java.util.List;

public record PriorityExecutor(PriorityContext request, List<Integer> answer) implements DecisionExecutor<PriorityContext.Choice> {
    @Override public PriorityContext.Choice apply() {
        PriorityContext.Choice choice = request.bindAnswer(answer);
        if (choice == null) { return null; }
        choice.ability.setActivatingPlayer(request.player);
        return choice;
    }
}
