package forge.rl.requests;

/** Applies one client answer for RL; never records teacher choices. */
@FunctionalInterface
public interface DecisionExecutor<R> {
    R apply();
}
