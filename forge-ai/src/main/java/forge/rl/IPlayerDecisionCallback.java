package forge.rl;

import java.util.List;
import java.util.Map;

/** Synchronous boundary: detached data in, integers out; native bindings stay in Java. */
@FunctionalInterface
public interface IPlayerDecisionCallback {
    List<Integer> choose(Map<String, Object> request);
}
