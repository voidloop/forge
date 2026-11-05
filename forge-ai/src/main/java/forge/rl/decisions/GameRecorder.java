package forge.rl.decisions;

import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.rl.IDecisionRecordCallback;
import forge.rl.StateReader;
import forge.rl.requests.DecisionRequest;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.Function;

/**
 * External lifecycle manager for game decision recording.
 */
public final class GameRecorder {
    private final Player player;
    private final IDecisionRecordCallback callback;
    private final Context context = new Context();
    private final StateReader session;
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
        ensureSession();
        R request = decision.prepare(player, session);
        return recordPrepared(request, null, () -> original.apply(request));
    }

    private void ensureSession() {
        requireHealthy();
        if (!sessionStarted) {
            callback.observe(session);
            sessionStarted = true;
        }
    }

    public <A> A record(Decision<?> decision, SpellAbility ability, Supplier<A> original) {
        ensureSession();
        return recordPrepared(decision.prepare(player, session), ability, original);
    }

    private <A> A recordPrepared(DecisionRequest request, SpellAbility ability, Supplier<A> original) {
        var question = (Map<?, ?>) request.data().get("question");
        String name = (String) question.get("kind");

        if (ability != null && context.activeKind != null) {
            if (isNestedSingleSelection(ability, name)) {
                return original.get();
            }
            throw new UnsupportedOperationException("Unexpected nested teacher selection");
        }

        String previousKind = context.activeKind;
        SpellAbility previousAbility = context.activeAbility;

        try {
            if (ability != null) {
                context.activeAbility = ability;
            }
            context.activeKind = name;

            callback.before(request.data());
            A answer = original.get();
            callback.after(request.encodeAnswer(answer));

            return answer;
        } catch (RuntimeException error) {
            fail(error);
            throw error;
        } finally {
            context.activeKind = previousKind;
            context.activeAbility = previousAbility;
        }
    }

    private boolean isNestedSingleSelection(SpellAbility ability, String kind) {
        return context.activeAbility == ability && kind.equals("single_entity")
                && (context.activeKind.equals("cards") || context.activeKind.equals("entities"));
    }

    private static final class Context {
        private SpellAbility activeAbility;
        private String activeKind;
    }

    public void recordExecuted(Function<StateReader, Map<String, Object>> result) {
        ensureSession();
        callback.executed(result.apply(session));
    }
}
