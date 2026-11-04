package forge.rl.decisions;

import java.util.List;

public record MulliganKeepRecorder() implements DecisionRecorder<MulliganQuestions.Keep, Boolean> {
    @Override public List<Integer> encode(MulliganQuestions.Keep request, Boolean keep) {
        return List.of(keep ? 1 : 0);
    }
}
