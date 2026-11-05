package forge.rl.decision.context;

/** Applies one client answer for RL; never records teacher choices. */
@FunctionalInterface
public interface DecisionExecutor<R> {
    R apply();
}
