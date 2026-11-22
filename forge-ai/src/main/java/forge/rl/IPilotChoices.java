package forge.rl;

import java.util.List;
import java.util.Map;

/** Synchronous choice at a stable decision boundary in a pilot game. */
public interface IPilotChoices {
    int pick(String stage, Map<String, Object> observation, List<Map<String, Object>> actions);
}
