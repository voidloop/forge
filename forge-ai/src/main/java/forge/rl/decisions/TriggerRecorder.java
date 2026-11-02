package forge.rl.decisions;

import java.util.ArrayList;
import java.util.List;

public record TriggerRecorder(TriggerRequest request) implements DecisionRecorder {
    @Override public List<Integer> encode() {
        var current = request.preparedStack();
        List<Integer> answer = new ArrayList<>();
        for (int i = request.bindings.size() - 1; i >= 0; i--) { answer.add(request.bindingIndex(current.get(i))); }
        return List.copyOf(answer);
    }
}
