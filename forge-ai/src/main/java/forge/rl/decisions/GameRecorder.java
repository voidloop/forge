package forge.rl.decisions;

import forge.game.spellability.SpellAbility;
import forge.rl.IDecisionRecordCallback;
import forge.rl.StateReader;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * External lifecycle manager for game decision recording.
 */
public final class GameRecorder {
    private final IDecisionRecordCallback callback;
    private final Context context = new Context();
    private RuntimeException failure;

    public GameRecorder(IDecisionRecordCallback callback) {
        this.callback = Objects.requireNonNull(callback);
    }

    public void start(StateReader view) {
        requireHealthy();
        callback.observe(view);
    }

    public void close() {
        try {
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

    public <A> A record(DecisionRequest request, Supplier<A> original) {
        return record(request, null, original);
    }

    public <A> A record(DecisionRequest request, SpellAbility ability, Supplier<A> original) {
        requireHealthy();
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
            callback.after(request.encode(answer));

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

    public void recordExecuted(Map<String, Object> data) {
        requireHealthy();
        callback.executed(data);
    }
}
