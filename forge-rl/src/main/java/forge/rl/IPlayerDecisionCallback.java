package forge.rl;

import java.util.List;
import java.util.Map;

/** The agent answers the current native request, never a precomputed action draft. */
public interface IPlayerDecisionCallback {
    List<Integer> choose(Map<String, Object> request);

    default void observe(ObservationFacade observation) { }

    default void cardsRevealed(boolean own, String zone, List<Integer> ids, List<String> names, List<Boolean> tokens) { }
    default void libraryTopVisible(boolean own, List<Integer> ids, List<String> names, List<Boolean> tokens) { }
}
