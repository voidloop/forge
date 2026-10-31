package forge.rl;

import java.util.List;
import java.util.Map;

/** Internal bridge: state/options facts in, unwrapped answer integers out; bindings stay in Java. */
@FunctionalInterface
public interface IPlayerDecisionCallback {
    List<Integer> choose(Map<String, Object> request);
    /** Internal lifecycle: bind Python memory after game setup, before choices. */
    default void observe(StateReader reader) { }
    default void close() { }
    /** Detached native execution facts, separate from client/teacher answers. */
    default void executed(Map<String, Object> result) { }
}
