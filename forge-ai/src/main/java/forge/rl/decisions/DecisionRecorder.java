package forge.rl.decisions;

import java.util.List;

/** Encodes one original final choice for the teacher as a detached protocol answer. */
@FunctionalInterface
public interface DecisionRecorder {
    List<Integer> encode();
}
