package forge.rl;

import forge.rl.decisions.PriorityRequest;
import forge.rl.decisions.AttackersRequest;
import forge.rl.decisions.BlockersRequest;
import forge.rl.decisions.CombatDamageRequest;
import forge.rl.decisions.CombatGraveyardRequest;
import forge.rl.decisions.CleanupRequests;
import forge.rl.decisions.SelectionRequest;
import forge.rl.decisions.ManaRequest;
import forge.rl.decisions.ManaPaymentRequest;
import forge.rl.decisions.MulliganRequests;
import forge.rl.decisions.StartingPlayerRequest;
import forge.rl.decisions.TriggerRequest;
import forge.rl.decisions.StartingPlayerRecorder;
import forge.rl.decisions.DecisionRecorder;
import forge.rl.decisions.MulliganKeepRecorder;
import forge.rl.decisions.SelectionRecorder;
import forge.rl.decisions.AttackersRecorder;
import forge.rl.decisions.BlockersRecorder;
import forge.rl.decisions.CombatDamageRecorder;
import forge.rl.decisions.CombatGraveyardRecorder;
import forge.rl.decisions.ManaRecorder;
import forge.rl.decisions.PriorityRecorder;
import forge.rl.decisions.TriggerRecorder;
import forge.rl.decisions.ManaColorRecorder;
import forge.rl.decisions.ManaPaymentRecorder;

import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.card.ColorSet;
import forge.card.mana.ManaCost;
import forge.game.cost.CostPartMana;
import forge.game.mana.ManaConversionMatrix;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.combat.Combat;
import forge.game.zone.ZoneType;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.util.collect.FCollectionView;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/** Records supported final decisions, including subordinate mana colors, through the public API. */
public final class PlayerControllerTeacher extends PlayerControllerAi {
    private final IDecisionRecordCallback callback;
    private SpellAbility activeAbility;
    private String activeKind;
    private final StateReader observation;
    private boolean observationStarted;
    private RuntimeException failure;
    private PriorityRequest.Choice pendingPriority;
    private PriorityRequest.Choice activePriority;
    private ManaRequest.Choice pendingMana;
    private ManaRequest.Choice activeMana;
    private ManaPaymentRequest payment;
    private TriggerRequest activeTrigger;

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
        return record(null, "starting_player", () -> new StartingPlayerRequest(player, observation, isFirstGame),
                request -> request.data, () -> super.chooseStartingPlayer(isFirstGame), StartingPlayerRecorder::new);
    }

    @Override public boolean mulliganKeepHand(Player first, int cardsToReturn) {
        if (failure != null) { throw failure; }
        try {
            if (activeKind != null) { throw new IllegalStateException("Nested mulligan decision"); }
            startObservation();
            var request = MulliganRequests.keep(player, observation, first, cardsToReturn);
            activeKind = "mulligan_keep";
            callback.before(request);
            boolean keep = super.mulliganKeepHand(first, cardsToReturn);
            if (failure != null) { throw failure; }
            callback.after(new MulliganKeepRecorder(keep).encode());
            return keep;
        } catch (RuntimeException error) { failure = error; throw error; }
        finally { activeKind = null; }
    }

    @Override public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int cardsToReturn) {
        if (failure != null) { throw failure; }
        try {
            if (activeKind != null) { throw new IllegalStateException("Nested tuck decision"); }
            startObservation();
            var request = MulliganRequests.tuck(player, observation, hand, cardsToReturn);
            activeKind = "mulligan_tuck";
            if (cardsToReturn != 0) { callback.before(request.data); }
            var chosen = super.tuckCardsViaMulligan(hand, cardsToReturn);
            if (failure != null) { throw failure; }
            var answer = new SelectionRecorder<>(request, chosen).encode();
            if (cardsToReturn != 0) { callback.after(answer); }
            return chosen;
        } catch (RuntimeException error) { failure = error; throw error; }
        finally { activeKind = null; }
    }

    private <T extends GameEntity, R> R record(SpellAbility ability, String kind, Supplier<SelectionRequest<T>> question,
            Supplier<R> original, Function<R, Iterable<T>> selected) {
        return record(ability, kind, question, request -> request.data, original,
                (request, result) -> new SelectionRecorder<>(request, selected.apply(result)));
    }

    private <Q, R> R record(SpellAbility ability, String kind, Supplier<Q> question,
            Function<Q, Map<String, Object>> data, Supplier<R> original, BiFunction<Q, R, DecisionRecorder> recorder) {
        if (failure != null) { throw failure; }
        if (isStartingPlayerBlocked(kind)) {
            failure = new IllegalStateException("Previous teacher decision is still active");
            throw failure;
        }
        // Native multiple selections call the public single selector internally.
        if (activeKind != null) {
            if (!isNestedSingleSelection(ability, kind)) {
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
            Q request = question.get();
            callback.before(data.apply(request));
            R result = original.get();
            if (failure != null) { throw failure; }
            callback.after(recorder.apply(request, result).encode());
            return result;
        } catch (RuntimeException error) { failure = error; throw error; }
        finally { activeAbility = null; activeKind = null; }
    }

    private boolean isStartingPlayerBlocked(String kind) {
        return kind.equals("starting_player") && (activeKind != null || pendingPriority != null
                || pendingMana != null || activeMana != null || activePriority != null);
    }

    private boolean isNestedSingleSelection(SpellAbility ability, String kind) {
        return activeAbility == ability && kind.equals("single_entity")
                && (activeKind.equals("cards") || activeKind.equals("entities"));
    }

    @Override public CardCollectionView chooseCardsToDiscardToMaximumHandSize(int numDiscard) {
        return record(null, "discard_cleanup", () -> CleanupRequests.create(player, observation, numDiscard),
                () -> super.chooseCardsToDiscardToMaximumHandSize(numDiscard), answer -> answer);
    }

    @Override public void declareAttackers(Player attacker, Combat combat) {
        if (failure != null) { throw failure; }
        try {
            if (activeKind != null || pendingPriority != null || pendingMana != null || activeMana != null || activePriority != null) {
                throw new IllegalStateException("Previous teacher decision is still active");
            }
            startObservation();
            var request = new AttackersRequest(player, observation, attacker, combat);
            activeKind = "declare_attackers";
            callback.before(request.data);
            super.declareAttackers(attacker, combat);
            if (failure != null) { throw failure; }
            callback.after(new AttackersRecorder(request).encode());
        } catch (RuntimeException error) { failure = error; throw error; }
        finally { activeKind = null; }
    }

    @Override public void declareBlockers(Player defender, Combat combat) {
        if (failure != null) { throw failure; }
        try {
            if (activeKind != null || pendingPriority != null || pendingMana != null || activeMana != null || activePriority != null) {
                throw new IllegalStateException("Previous teacher decision is still active");
            }
            startObservation();
            var request = new BlockersRequest(player, observation, defender, combat);
            activeKind = "declare_blockers";
            callback.before(request.data);
            super.declareBlockers(defender, combat);
            if (failure != null) { throw failure; }
            callback.after(new BlockersRecorder(request).encode());
        } catch (RuntimeException error) { failure = error; throw error; }
        finally { activeKind = null; }
    }

    @Override public Map<Card, Integer> assignCombatDamage(Card source, CardCollectionView targets, CardCollectionView remaining,
            int damage, GameEntity defender, boolean overrideOrder) {
        if (failure != null) { throw failure; }
        try {
            if (activeKind != null || pendingPriority != null || pendingMana != null || activeMana != null || activePriority != null) {
                throw new IllegalStateException("Previous teacher decision is still active");
            }
            startObservation();
            var request = new CombatDamageRequest(player, observation, source, targets, remaining, damage, defender, overrideOrder);
            activeKind = "combat_damage";
            callback.before(request.data);
            var chosen = super.assignCombatDamage(source, targets, remaining, damage, defender, overrideOrder);
            if (failure != null) { throw failure; }
            callback.after(new CombatDamageRecorder(request, chosen).encode());
            return chosen;
        } catch (RuntimeException error) { failure = error; throw error; }
        finally { activeKind = null; }
    }

    @Override public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone, SpellAbility source) {
        if (failure != null) { throw failure; }
        try {
            if (activeKind != null || pendingPriority != null || pendingMana != null || activeMana != null || activePriority != null) {
                throw new IllegalStateException("Previous teacher decision is still active");
            }
            startObservation();
            var request = new CombatGraveyardRequest(player, observation, cards, destinationZone, source);
            activeKind = "combat_graveyard_order";
            callback.before(request.data);
            var chosen = super.orderMoveToZoneList(cards, destinationZone, source);
            if (failure != null) { throw failure; }
            callback.after(new CombatGraveyardRecorder(request, chosen).encode());
            return chosen;
        } catch (RuntimeException error) { failure = error; throw error; }
        finally { activeKind = null; }
    }

    @Override public List<SpellAbility> chooseSpellAbilityToPlay() {
        if (failure != null) { throw failure; }
        try {
            if (activeKind != null || pendingPriority != null || pendingMana != null || activeMana != null || activePriority != null) {
                throw new IllegalStateException("Previous teacher decision is still active");
            }
            startObservation();
            if (ManaRequest.hasSource(player)) {
                ManaRequest request = new ManaRequest(player, observation);
                activeKind = "priority_mana";
                callback.before(request.data);
                List<SpellAbility> chosen = super.chooseSpellAbilityToPlay();
                if (failure != null) { throw failure; }
                List<Integer> answer = new ManaRecorder(request, chosen).encode();
                pendingMana = chosen == null ? null : request.selected();
                callback.after(answer);
                return chosen;
            }
            PriorityRequest request = new PriorityRequest(player, observation);
            activeKind = "priority";
            callback.before(request.data);
            List<SpellAbility> chosen = super.chooseSpellAbilityToPlay();
            if (failure != null) { throw failure; }
            List<Integer> answer = new PriorityRecorder(request, chosen).encode();
            pendingPriority = request.bindChoice(answer);
            callback.after(answer);
            return chosen;
        } catch (RuntimeException error) { failure = error; throw error; }
        finally { activeAbility = null; activeKind = null; }
    }

    @Override public void orderAndPlaySimultaneousSa(List<SpellAbility> abilities) {
        if (failure != null) { throw failure; }
        try {
            if (hasActiveDecision()) { throw new IllegalStateException("Nested teacher trigger preparation"); }
            startObservation();
            var request = new TriggerRequest(player, observation, abilities);
            activeTrigger = request;
            activeKind = "trigger_prepare";
            callback.before(request.data);
            super.orderAndPlaySimultaneousSa(abilities);
            if (failure != null) { throw failure; }
            callback.after(new TriggerRecorder(request).encode());
        } catch (RuntimeException error) { failure = error; throw error; }
        finally { activeTrigger = null; activeKind = null; }
    }

    private boolean hasActiveDecision() {
        return activeKind != null || pendingPriority != null || pendingMana != null
                || activeMana != null || activePriority != null || payment != null || activeTrigger != null;
    }

    @Override public List<SpellAbility> orderSimultaneousSa(List<SpellAbility> abilities) {
        if (failure != null) { throw failure; }
        try {
            if (activeTrigger == null) { throw new UnsupportedOperationException("Unbound teacher trigger order"); }
            activeTrigger.requireBoundOrder(abilities);
            return super.orderSimultaneousSa(abilities);
        } catch (RuntimeException error) { failure = error; throw error; }
    }

    @Override public void playSpellAbilityNoStack(SpellAbility ability, boolean setupTargets) {
        if (failure != null) { throw failure; }
        try {
            if (hasActiveDecision()) { throw new IllegalStateException("Nested teacher trigger resolution"); }
            TriggerRequest.requireResolution(player, ability, setupTargets);
            activeAbility = ability;
            activeKind = "trigger_resolution";
            super.playSpellAbilityNoStack(ability, setupTargets);
            if (failure != null) { throw failure; }
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
        activePriority = pendingPriority;
        pendingPriority = null;
        try {
            if (activePriority == null) { throw new IllegalArgumentException("Unbound teacher priority action"); }
            activePriority.before(player, ability);
            if (activePriority.isCast()) { callback.beginExecution(); }
            boolean reported = super.playChosenSpellAbility(ability);
            if (failure != null) { throw failure; }
            activePriority.after(player, reported);
            if (activePriority.isCast()) { callback.executed(activePriority.result(player, observation, reported)); }
            return reported;
        } catch (RuntimeException error) { failure = error; throw error; }
        finally { activePriority = null; }
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
            callback.after(new ManaColorRecorder(activeMana, chosen).encode());
            return chosen;
        } catch (RuntimeException error) { failure = error; throw error; }
        finally { activeKind = null; }
    }

    @Override public boolean payManaCost(ManaCost cost, CostPartMana part, SpellAbility ability,
            String prompt, ManaConversionMatrix matrix, boolean effect) {
        if (failure != null) { throw failure; }
        if (isBoundZeroPayment(cost, ability)) {
            try { return super.payManaCost(cost, part, ability, prompt, matrix, effect); }
            catch (RuntimeException error) { failure = error; throw error; }
        }
        try {
            if (payment != null || activeKind != null || activeMana != null || pendingPriority != null || pendingMana != null) {
                throw new UnsupportedOperationException("Nested teacher mana payment");
            }
            startObservation();
            var request = new ManaPaymentRequest(player, observation, cost, part, ability, matrix, effect);
            payment = request;
            callback.before(request.data);
            boolean paid = super.payManaCost(cost, part, ability, prompt, matrix, effect);
            if (failure != null) { throw failure; }
            callback.after(new ManaPaymentRecorder(request, paid).encode());
            return paid;
        } catch (RuntimeException error) { failure = error; throw error; }
        finally { payment = null; }
    }

    private boolean isBoundZeroPayment(ManaCost cost, SpellAbility ability) {
        return cost.isZero() && (activePriority != null && activePriority.isMana() && activePriority.ability == ability
                || activeMana != null && activeMana.ability == ability
                || payment != null && payment.isSource(ability)
                || isBoundTriggerPayment(ability));
    }

    private boolean isBoundTriggerPayment(SpellAbility ability) {
        return activeTrigger != null && activeTrigger.isSource(ability)
                || "trigger_resolution".equals(activeKind) && activeAbility == ability;
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
