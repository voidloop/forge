package forge.rl.decisions;

import java.util.List;
import java.util.Objects;

public record ConfirmActionRecorder() implements DecisionRecorder<ConfirmActionQuestion, Boolean> {
    @Override public List<Integer> encode(ConfirmActionQuestion request, Boolean answer) {
        request.requireBinding();
        return List.of(Objects.requireNonNull(answer, "Missing native confirmation") ? 1 : 0);
    }
}
