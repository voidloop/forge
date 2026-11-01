package forge.rl;

import com.google.common.eventbus.Subscribe;
import forge.game.event.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Passive, bounded evidence for an original-AI worker; never changes the game or its RNG. */
public final class BaselineTrace {
    private final int maxTurns;
    private final int maxEvents;
    private int events;
    private int turn;
    private String limit = "";
    private String failure = "";
    private final List<String> milestones = new ArrayList<>();

    public BaselineTrace(int maxTurns, int maxEvents) {
        if (maxTurns <= 0 || maxEvents <= 0) {
            throw new IllegalArgumentException("Baseline limits must be positive");
        }
        this.maxTurns = maxTurns;
        this.maxEvents = maxEvents;
    }

    @Subscribe public synchronized void event(GameEvent event) {
        if (!limit.isEmpty() || !failure.isEmpty()) { return; }
        try {
            events++;
            if (event instanceof GameEventTurnBegan began) { turn = began.turnNumber(); }
            if (events > maxEvents) { limit = "events"; }
            else if (turn > maxTurns) { limit = "turns"; }
            if (event instanceof GameEventGameStarted || event instanceof GameEventMulligan
                    || event instanceof GameEventTurnBegan || event instanceof GameEventGameOutcome
                    || event instanceof GameEventGameFinished) {
                milestones.add(event.getClass().getSimpleName() + ": " + event);
            }
        } catch (RuntimeException error) {
            // EventBus swallows subscriber exceptions; the worker must see observation failures.
            failure = error.toString();
        }
    }

    public synchronized Map<String, Object> snapshot() {
        return Map.of("events", events, "turn", turn, "limit", limit, "failure", failure,
                "milestones", List.copyOf(milestones));
    }
}
