package forge.rl.decisions;

import java.util.List;

/** Encodes an original final choice; Void denotes a choice read from bound native objects. */
@FunctionalInterface
public interface DecisionRecorder<R, A> {
    List<Integer> encode(R request, A answer);
}
