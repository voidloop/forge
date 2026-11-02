package forge.rl.decisions;

import java.util.List;

public record MulliganKeepRecorder() implements DecisionRecorder<MulliganRequests.Keep, Boolean> {
    @Override public List<Integer> encode(MulliganRequests.Keep request, Boolean keep) {
        return List.of(keep ? 1 : 0);
    }
}
