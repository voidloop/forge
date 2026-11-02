package forge.rl.decisions;

import java.util.List;
import java.util.Objects;

public record ConfirmActionRecorder() implements DecisionRecorder<ConfirmActionRequest, Boolean> {
    @Override public List<Integer> encode(ConfirmActionRequest request, Boolean answer) {
        request.requireBinding();
        return List.of(Objects.requireNonNull(answer, "Missing native confirmation") ? 1 : 0);
    }
}
