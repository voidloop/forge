package forge.rl.decisions;

import java.util.List;

public record MulliganKeepRecorder(boolean keep) implements DecisionRecorder {
    @Override public List<Integer> encode() {
        return List.of(keep ? 1 : 0);
    }
}
