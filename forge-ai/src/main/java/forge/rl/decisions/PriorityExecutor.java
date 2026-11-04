package forge.rl.decisions;

import java.util.List;

public record PriorityExecutor(PriorityQuestion request, List<Integer> answer) implements DecisionExecutor<PriorityQuestion.Choice> {
    @Override public PriorityQuestion.Choice apply() {
        PriorityQuestion.Choice choice = request.bindChoice(answer);
        if (choice == null) { return null; }
        choice.ability.setActivatingPlayer(request.player);
        return choice;
    }
}
