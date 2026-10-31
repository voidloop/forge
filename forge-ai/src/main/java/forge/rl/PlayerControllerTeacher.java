package forge.rl;

import forge.rl.decisions.BasicLandDecision;
import forge.rl.decisions.CleanupDecision;
import forge.rl.decisions.DecisionRequest;
import forge.rl.decisions.ManaDecision;
import forge.rl.decisions.MulliganDecision;
import forge.rl.decisions.StartingPlayerDecision;

import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.card.ColorSet;
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

/** Records supported final decisions, including subordinate mana colors, through the public API. */
public final class PlayerControllerTeacher extends PlayerControllerAi {
    private final IDecisionRecordCallback callback;
    private SpellAbility activeAbility;
    private String activeKind;
    private final StateReader observation;
    private boolean observationStarted;
    private RuntimeException failure;
    private BasicLandDecision.Choice pendingLand;
    private ManaDecision.Choice pendingMana;
    private ManaDecision.Choice activeMana;

    public PlayerControllerTeacher(Game game, Player player, LobbyPlayerAi lobby, IDecisionRecordCallback callback) {
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

    @Override public void awaitNextInput() {
        try {
            startObservation();
            super.awaitNextInput();
        } catch (RuntimeException error) { failure = error; throw error; }
    }

    @Override public Player chooseStartingPlayer(boolean isFirstGame) {
        if (failure != null) { throw failure; }
        try {
            if (activeKind != null || pendingLand != null || pendingMana != null || activeMana != null) {
                throw new IllegalStateException("Previous teacher decision is still active");
            }
            startObservation();
            var request = new StartingPlayerDecision(player, observation, isFirstGame);
            activeKind = "starting_player";
            callback.before(request.data);
            Player chosen = super.chooseStartingPlayer(isFirstGame);
            if (failure != null) { throw failure; }
            callback.after(request.encode(chosen));
            return chosen;
        } catch (RuntimeException error) { failure = error; throw error; }
        finally { activeKind = null; }
    }

    @Override public boolean mulliganKeepHand(Player first, int cardsToReturn) {
        if (failure != null) { throw failure; }
        try {
            if (activeKind != null) { throw new IllegalStateException("Nested mulligan decision"); }
            startObservation();
            var request = MulliganDecision.keep(player, observation, first, cardsToReturn);
            activeKind = "mulligan_keep";
            callback.before(request);
            boolean keep = super.mulliganKeepHand(first, cardsToReturn);
            if (failure != null) { throw failure; }
            callback.after(List.of(keep ? 1 : 0));
            return keep;
        } catch (RuntimeException error) { failure = error; throw error; }
        finally { activeKind = null; }
    }

    @Override public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int cardsToReturn) {
        if (failure != null) { throw failure; }
        try {
            if (activeKind != null) { throw new IllegalStateException("Nested tuck decision"); }
            startObservation();
            var request = MulliganDecision.tuck(player, observation, hand, cardsToReturn);
            activeKind = "mulligan_tuck";
            if (cardsToReturn != 0) { callback.before(request.data); }
            var chosen = super.tuckCardsViaMulligan(hand, cardsToReturn);
            if (failure != null) { throw failure; }
            var answer = request.encode(chosen);
            if (cardsToReturn != 0) { callback.after(answer); }
            return chosen;
        } catch (RuntimeException error) { failure = error; throw error; }
        finally { activeKind = null; }
    }

    private <T extends GameEntity, R> R record(SpellAbility ability, String kind, Supplier<DecisionRequest<T>> question,
            Supplier<R> original, Function<R, Iterable<T>> selected) {
        if (failure != null) { throw failure; }
        // Native multiple selections call the public single selector internally.
        if (activeKind != null) {
            if (activeAbility != ability || !kind.equals("single_entity")
                    || !(activeKind.equals("cards") || activeKind.equals("entities"))) {
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
            DecisionRequest<T> request = question.get();
            callback.before(request.data);
            R result = original.get();
            if (failure != null) { throw failure; }
            callback.after(request.encode(selected.apply(result)));
            return result;
        } catch (RuntimeException error) { failure = error; throw error; }
        finally { activeAbility = null; activeKind = null; }
    }

    @Override public CardCollectionView chooseCardsToDiscardToMaximumHandSize(int numDiscard) {
        return record(null, "discard_cleanup", () -> CleanupDecision.create(player, observation, numDiscard),
                () -> super.chooseCardsToDiscardToMaximumHandSize(numDiscard), answer -> answer);
    }

    @Override public List<SpellAbility> chooseSpellAbilityToPlay() {
        if (failure != null) { throw failure; }
        try {
            if (activeKind != null || pendingLand != null || pendingMana != null || activeMana != null) {
                throw new IllegalStateException("Previous teacher decision is still active");
            }
            startObservation();
            if (ManaDecision.hasSource(player)) {
                ManaDecision request = new ManaDecision(player, observation);
                activeKind = "priority_mana";
                callback.before(request.data);
                List<SpellAbility> chosen = super.chooseSpellAbilityToPlay();
                if (failure != null) { throw failure; }
                List<Integer> answer = request.encode(chosen);
                pendingMana = chosen == null ? null : request.selected();
                callback.after(answer);
                return chosen;
            }
            BasicLandDecision request = new BasicLandDecision(player, observation);
            activeKind = "priority_basic_lands";
            callback.before(request.data);
            List<SpellAbility> chosen = super.chooseSpellAbilityToPlay();
            if (failure != null) { throw failure; }
            List<Integer> answer = request.encode(chosen);
            if (chosen != null) { pendingLand = new BasicLandDecision.Choice(chosen.get(0), chosen.get(0).getHostCard()); }
            callback.after(answer);
            return chosen;
        } catch (RuntimeException error) { failure = error; throw error; }
        finally { activeAbility = null; activeKind = null; }
    }

    @Override public boolean playChosenSpellAbility(SpellAbility ability) {
        if (failure != null) { throw failure; }
        if (pendingMana != null) {
            activeMana = pendingMana;
            pendingMana = null;
            try {
                activeMana.before(player, ability);
                boolean reported = super.playChosenSpellAbility(ability);
                if (failure != null) { throw failure; }
                activeMana.after(player, reported);
                return reported;
            } catch (RuntimeException error) { failure = error; throw error; }
            finally { activeMana = null; }
        }
        BasicLandDecision.Choice choice = pendingLand;
        pendingLand = null;
        try {
            if (choice == null) { throw new IllegalArgumentException("Unbound teacher land action"); }
            int played = choice.before(player, ability);
            boolean reported = super.playChosenSpellAbility(ability);
            choice.after(player, played, reported);
            return reported;
        } catch (RuntimeException error) { failure = error; throw error; }
    }

    @Override public byte chooseColor(String message, SpellAbility ability, ColorSet colors) {
        if (failure != null) { throw failure; }
        try {
            if (activeMana == null || activeKind != null) { throw new UnsupportedOperationException("Unbound teacher mana color"); }
            var request = activeMana.colorRequest(player, observation, message, ability, colors);
            activeKind = "mana_color";
            callback.before(request);
            byte chosen = super.chooseColor(message, ability, colors);
            if (failure != null) { throw failure; }
            callback.after(activeMana.encodeColor(chosen));
            return chosen;
        } catch (RuntimeException error) { failure = error; throw error; }
        finally { activeKind = null; }
    }

    @Override public <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> options, DelayedReveal reveal,
            SpellAbility sa, String title, boolean optional, Player related, Map<String, Object> params) {
        return record(sa, "single_entity", () -> new DecisionRequest<>(player, observation, "single_entity", options, title, 1, 1, optional, reveal, related, params),
                () -> super.chooseSingleEntityForEffect(options, reveal, sa, title, optional, related, params),
                answer -> answer == null ? List.of() : List.of(answer));
    }

    @Override public CardCollectionView chooseCardsForEffect(CardCollectionView options, SpellAbility sa,
            String title, int min, int max, boolean optional, Map<String, Object> params) {
        return record(sa, "cards", () -> new DecisionRequest<>(player, observation, "cards", options, title, min, max, optional, null, null, params),
                () -> super.chooseCardsForEffect(options, sa, title, min, max, optional, params), answer -> answer);
    }

    @Override public <T extends GameEntity> List<T> chooseEntitiesForEffect(FCollectionView<T> options, int min, int max,
            DelayedReveal reveal, SpellAbility sa, String title, Player related, Map<String, Object> params) {
        return record(sa, "entities", () -> new DecisionRequest<>(player, observation, "entities", options, title, min, max, min == 0, reveal, related, params),
                () -> super.chooseEntitiesForEffect(options, min, max, reveal, sa, title, related, params), answer -> answer);
    }
}
