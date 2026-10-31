package forge.rl;

import java.util.List;
import java.util.Map;

/** A question is pending until after receives its complete final answer. */
public interface ISelectionRecordCallback {
    void before(Map<String, Object> request);
    void after(List<Integer> answer);
    /** Internal lifecycle: bind Python memory after game setup, before choices. */
    default void observe(StateReader reader) { }
    default void close() { }
}
