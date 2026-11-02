package forge.rl.decisions;

import java.util.List;

public record PriorityExecutor(PriorityRequest request, List<Integer> answer) implements DecisionExecutor<PriorityRequest.Choice> {
    @Override public PriorityRequest.Choice apply() {
        PriorityRequest.Choice choice = request.bindChoice(answer);
        if (choice == null) { return null; }
        choice.ability.setActivatingPlayer(request.player);
        return choice;
    }
}
