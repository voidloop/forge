package forge.rl.decisions;

import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.rl.IDecisionRecordCallback;
import forge.rl.StateReader;
import forge.rl.requests.DecisionRequest;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * External lifecycle manager for game decision recording.
 */
public final class GameRecorder {
    private final Player player;
    private final IDecisionRecordCallback callback;
    private final StateReader session;
    private SpellAbility activeAbility;
    private String activeKind;
    private boolean sessionStarted;
    private RuntimeException failure;

    public GameRecorder(Player player, IDecisionRecordCallback callback) {
        this.player = Objects.requireNonNull(player);
        this.callback = Objects.requireNonNull(callback);
        this.session = new StateReader(player, 0L);
    }

    public void close() {
        try {
            session.close();
            callback.close();
        } finally {
            if (failure == null) {
                failure = new IllegalStateException("Recorder is closed");
            }
        }
    }

    public void fail(RuntimeException error) {
        if (failure == null) {
            failure = error;
        }
    }

    public void requireHealthy() {
        if (failure != null) {
            throw failure;
        }
    }

    public <A> A record(Decision<?> decision, Supplier<A> original) {
        return record(decision, null, original);
    }

    public <R extends DecisionRequest, A> A record(Decision<R> decision, Function<R, A> original) {
        return record(decision, null, original);
    }

    private void ensureSession() {
        requireHealthy();
        if (!sessionStarted) {
            callback.observe(session);
            sessionStarted = true;
        }
    }

    public <A> A record(Decision<?> decision, SpellAbility ability, Supplier<A> original) {
        return record(decision, ability, request -> original.get());
    }

    private <R extends DecisionRequest, A> A record(Decision<R> decision, SpellAbility ability,
            Function<R, A> original) {
        ensureSession();
        R request = decision.prepare(player, session);
        String name = decisionKind(request);

        if (ability != null && activeKind != null) {
            if (isNestedSingleSelection(ability, name)) {
                return original.apply(request);
            }
            throw new UnsupportedOperationException("Unexpected nested teacher selection");
        }

        String previousKind = activeKind;
        SpellAbility previousAbility = activeAbility;

        try {
            if (ability != null) {
                activeAbility = ability;
            }
            activeKind = name;

            callback.before(request.data());
            A answer = original.apply(request);
            callback.after(request.encodeAnswer(answer));

            return answer;
        } catch (RuntimeException error) {
            fail(error);
            throw error;
        } finally {
            activeKind = previousKind;
            activeAbility = previousAbility;
        }
    }

    private boolean isNestedSingleSelection(SpellAbility ability, String kind) {
        return activeAbility == ability && kind.equals("single_entity")
                && (activeKind.equals("cards") || activeKind.equals("entities"));
    }

    private static String decisionKind(DecisionRequest request) {
        var question = (Map<?, ?>) request.data().get("question");
        return (String) question.get("kind");
    }

    public void recordExecuted(Function<StateReader, Map<String, Object>> result) {
        ensureSession();
        callback.executed(result.apply(session));
    }
}
