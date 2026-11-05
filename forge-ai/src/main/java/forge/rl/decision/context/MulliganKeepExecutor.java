package forge.rl.decision.context;

import java.util.List;

public record MulliganKeepExecutor(List<Integer> answer) implements DecisionExecutor<Boolean> {
    @Override public Boolean apply() {
        if (!isKeepAnswer()) {
            throw new IllegalArgumentException("Invalid keep response");
        }
        return answer.get(0) == 1;
    }

    private boolean isKeepAnswer() {
        return answer.size() == 1 && answer.get(0) >= 0 && answer.get(0) <= 1;
    }
}
