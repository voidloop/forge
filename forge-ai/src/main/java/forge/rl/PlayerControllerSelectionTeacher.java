package forge.rl;

import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.CardCollectionView;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.util.collect.FCollectionView;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/** Records only the final answers of the three supported public selection methods. */
public final class PlayerControllerSelectionTeacher extends PlayerControllerAi {
    private final ISelectionRecordCallback callback;
    private SpellAbility activeAbility;
    private String activeKind;
    private final StateReader observation;
    private boolean observationStarted;
    private RuntimeException failure;

    public PlayerControllerSelectionTeacher(Game game, Player player, LobbyPlayerAi lobby, ISelectionRecordCallback callback) {
        super(game, player, lobby);
        this.callback = Objects.requireNonNull(callback);
        // Private replay setup: observable aliases follow the same visible history, never engine RNG.
        this.observation = new StateReader(player, 0L);
    }

    public void startObservation() {
        if (failure != null) { throw failure; }
        if (!observationStarted) { callback.observe(observation); observationStarted = true; }
    }

    public void closeObservation() {
        try { callback.close(); }
        finally {
            observation.close();
            if (failure == null) { failure = new IllegalStateException("Controller observation is closed"); }
        }
    }

    private <T extends GameEntity, R> R record(SpellAbility ability, String kind, Supplier<SelectionRequest<T>> question,
            Supplier<R> original, Function<R, Iterable<T>> selected) {
        if (failure != null) { throw failure; }
        // Native multiple selections call the public single selector internally.
        if (activeKind != null) {
            if (activeAbility != ability || !kind.equals("single_entity") || activeKind.equals("single_entity")) {
                failure = new UnsupportedOperationException("Unexpected nested teacher selection");
                throw failure;
            }
            try { return original.get(); }
            catch (RuntimeException error) { failure = error; throw error; }
        }
        activeAbility = ability;
        activeKind = kind;
        try {
            startObservation();
            SelectionRequest<T> request = question.get();
            callback.before(request.data);
            R result = original.get();
            if (failure != null) { throw failure; }
            callback.after(request.encode(selected.apply(result)));
            return result;
        } catch (RuntimeException error) { failure = error; throw error; }
        finally { activeAbility = null; activeKind = null; }
    }

    @Override public <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> options, DelayedReveal reveal,
            SpellAbility sa, String title, boolean optional, Player related, Map<String, Object> params) {
        return record(sa, "single_entity", () -> new SelectionRequest<>(player, observation, "single_entity", options, title, 1, 1, optional, reveal, related, params),
                () -> super.chooseSingleEntityForEffect(options, reveal, sa, title, optional, related, params),
                answer -> answer == null ? List.of() : List.of(answer));
    }

    @Override public CardCollectionView chooseCardsForEffect(CardCollectionView options, SpellAbility sa,
            String title, int min, int max, boolean optional, Map<String, Object> params) {
        return record(sa, "cards", () -> new SelectionRequest<>(player, observation, "cards", options, title, min, max, optional, null, null, params),
                () -> super.chooseCardsForEffect(options, sa, title, min, max, optional, params), answer -> answer);
    }

    @Override public <T extends GameEntity> List<T> chooseEntitiesForEffect(FCollectionView<T> options, int min, int max,
            DelayedReveal reveal, SpellAbility sa, String title, Player related, Map<String, Object> params) {
        return record(sa, "entities", () -> new SelectionRequest<>(player, observation, "entities", options, title, min, max, min == 0, reveal, related, params),
                () -> super.chooseEntitiesForEffect(options, min, max, reveal, sa, title, related, params), answer -> answer);
    }
}
