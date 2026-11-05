package forge.rl;

import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.rl.decision.context.DecisionContext;
import forge.rl.decision.Decision;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

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

    private void ensureSession() {
        requireHealthy();
        if (!sessionStarted) {
            callback.observe(session);
            sessionStarted = true;
        }
    }

    public <C extends DecisionContext, A> A record(Decision<C> decision, Function<C, A> original) {
        ensureSession();
        C context = decision.prepare(player, session);
        String name = context.kind();
        SpellAbility ability = decision.ability();

        if (ability != null && activeKind != null) {
            if (isNestedSingleSelection(ability, name)) {
                return original.apply(context);
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

            callback.before(context.encodeQuestion());
            A answer = original.apply(context);
            callback.after(context.encodeAnswer(answer));

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

    public void recordExecuted(Function<StateReader, Map<String, Object>> result) {
        ensureSession();
        callback.executed(result.apply(session));
    }
}
