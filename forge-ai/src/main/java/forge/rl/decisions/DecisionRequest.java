package forge.rl.decisions;

import java.util.Map;

/** Detached question data shared by native decision requests. */
public interface DecisionRequest {
    Map<String, Object> data();
}
