package forge.rl.decisions;

import java.util.List;

public record PriorityExecutor(PriorityDecision request, List<Integer> answer) implements DecisionExecutor<PriorityDecision.Choice> {
    @Override public PriorityDecision.Choice apply() {
        PriorityDecision.Choice choice = request.bindChoice(answer);
        if (choice == null) { return null; }
        choice.ability.setActivatingPlayer(request.player);
        return choice;
    }
}
