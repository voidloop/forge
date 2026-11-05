package forge.rl.decision.context;

import java.util.List;

public record ConfirmActionExecutor(ConfirmActionContext request, List<Integer> answer) implements DecisionExecutor<Boolean> {
    @Override public Boolean apply() {
        request.requireBinding();
        if (!isBooleanAnswer()) { throw new IllegalArgumentException("Invalid confirmation response"); }
        return answer.get(0) == 1;
    }

    private boolean isBooleanAnswer() {
        return answer.size() == 1 && answer.get(0) >= 0 && answer.get(0) <= 1;
    }
}
