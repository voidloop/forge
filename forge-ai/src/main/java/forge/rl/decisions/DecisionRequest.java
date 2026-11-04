package forge.rl.decisions;

import java.util.List;
import java.util.Map;

/** Detached question data shared by native decision requests. */
public interface DecisionRequest {
    Map<String, Object> data();
    default List<Integer> encode(Object answer) {
        throw new UnsupportedOperationException("Encoding not implemented for " + getClass().getSimpleName());
    }
}
