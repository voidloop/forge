package forge.rl;

import forge.rl.decisions.PriorityRequest;
import forge.rl.decisions.AttackersRequest;
import forge.rl.decisions.BlockersRequest;
import forge.rl.decisions.CombatDamageRequest;
import forge.rl.decisions.CombatGraveyardRequest;
import forge.rl.decisions.CleanupRequests;
import forge.rl.decisions.SelectionRequest;
import forge.rl.decisions.ConfirmActionRequest;
import forge.rl.decisions.ConfirmActionRecorder;
import forge.rl.decisions.ManaRequest;
import forge.rl.decisions.ManaPaymentRequest;
import forge.rl.decisions.MulliganRequests;
import forge.rl.decisions.StartingPlayerRequest;
import forge.rl.decisions.TriggerRequest;
import forge.rl.decisions.StartingPlayerRecorder;
import forge.rl.decisions.DecisionRecorder;
import forge.rl.decisions.DecisionRequest;
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
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.SpellAbility;
import forge.util.collect.FCollectionView;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Records supported final decisions, including subordinate mana colors, through the public API. */
public final class PlayerControllerTeacher extends PlayerControllerAi {
    private final IDecisionRecordCallback callback;
    private SpellAbility activeAbility;
    private String activeKind;
    private final StateReader observation;
    private boolean observationStarted;
    private boolean observationClosed;
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
            observationClosed = true;
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
        return withDecision("starting_player", () -> record(
                () -> new StartingPlayerRequest(player, observation, isFirstGame),
                () -> super.chooseStartingPlayer(isFirstGame),
                new StartingPlayerRecorder()));
    }

    @Override public boolean mulliganKeepHand(Player first, int cardsToReturn) {
        return withDecision("mulligan_keep", () -> record(
                () -> MulliganRequests.keep(player, observation, first, cardsToReturn),
                () -> super.mulliganKeepHand(first, cardsToReturn),
                new MulliganKeepRecorder()));
    }

    @Override public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int cardsToReturn) {
        return withDecision("mulligan_tuck", () -> record(
                () -> MulliganRequests.tuck(player, observation, hand, cardsToReturn),
                () -> super.tuckCardsViaMulligan(hand, cardsToReturn), new SelectionRecorder<Card>()::encode,
                cardsToReturn != 0));
    }

    private <R extends DecisionRequest, A> A record(
            Supplier<R> prepare, Supplier<A> original, DecisionRecorder<R, A> recorder) {
        return record(prepare, original, recorder, true);
    }

    private <R extends DecisionRequest, A> A record(
            Supplier<R> prepare, Supplier<A> original, DecisionRecorder<R, A> recorder, boolean emitCallbacks) {
        R request = prepare.get();
        if (emitCallbacks) { callback.before(request.data()); }
        A answer = original.get();
        List<Integer> encoded = recorder.encode(request, answer);
        if (emitCallbacks) { callback.after(encoded); }
        return answer;
    }

    private <A> A withDecision(String kind, Supplier<A> operation) {
        if (hasUnfinishedAction()) {
            throw new IllegalStateException("Previous teacher decision is still active");
        }
        String previousKind = activeKind;
        activeKind = kind;
        try {
            if (observationClosed) { throw new IllegalStateException("Controller observation is closed"); }
            if (!observationStarted) {
                callback.observe(observation);
                observationStarted = true;
            }
            return operation.get();
        } finally { activeKind = previousKind; }
    }

    private <R extends DecisionRequest, A> A recordLegacy(
            String name, Supplier<R> prepare, Supplier<A> original, DecisionRecorder<R, A> recorder) {
        return recordLegacy(name, prepare, original, recorder, true);
    }

    private <R extends DecisionRequest, A> A recordLegacy(
            String name, Supplier<R> prepare, Supplier<A> original, DecisionRecorder<R, A> recorder,
            boolean emitCallbacks) {
        return withLegacyDecision(() -> {
            try {
                beginDecision(name);
                if (!isStartupDecision(name)) { requireHealthy(); }
                R request = prepare.get();
                if (!name.equals("mana_payment")) { activeKind = name; }
                if (emitCallbacks) { callback.before(request.data()); }
                if (!isStartupDecision(name)) { requireHealthy(); }
                A answer = original.get();
                if (!isStartupDecision(name)) { requireHealthy(); }
                List<Integer> encoded = recorder.encode(request, answer);
                if (emitCallbacks) { callback.after(encoded); }
                if (!isStartupDecision(name)) { requireHealthy(); }
                return answer;
            } catch (RuntimeException error) {
                if (!isStartupDecision(name)) { failure = error; }
                throw error;
            }
        });
    }

    private <A> A withLegacyDecision(Supplier<A> operation) {
        String previousKind = activeKind;
        SpellAbility previousAbility = activeAbility;
        ManaPaymentRequest previousPayment = payment;
        TriggerRequest previousTrigger = activeTrigger;
        try { return operation.get(); }
        finally {
            activeKind = previousKind;
            activeAbility = previousAbility;
            payment = previousPayment;
            activeTrigger = previousTrigger;
        }
    }

    private void beginDecision(String name) {
        if (!isStartupDecision(name)) { requireHealthy(); }
        requireDecisionContext(name);
        if (bindsBeforeObservation(name)) { activeKind = name; }
        if (!name.equals("mana_color")) { startObservation(); }
    }

    private <R extends DecisionRequest, A> A recordLegacy(SpellAbility ability, String name, Supplier<R> prepare,
            Supplier<A> original, DecisionRecorder<R, A> recorder) {
        requireHealthy();
        // Native multiple selections call the public single selector internally.
        if (activeKind != null) {
            if (!isNestedSingleSelection(ability, name)) {
                failure = new UnsupportedOperationException("Unexpected nested teacher selection");
                throw failure;
            }
            try { return original.get(); }
            catch (RuntimeException error) { failure = error; throw error; }
        }
        return withLegacyDecision(() -> {
            activeAbility = ability;
            return recordLegacy(name, prepare, original, recorder);
        });
    }

    private void requireHealthy() {
        if (failure != null) { throw failure; }
    }

    private void requireDecisionContext(String name) {
        switch (name) {
            case "trigger_prepare" -> {
                if (hasActiveDecision()) { throw new IllegalStateException("Nested teacher trigger preparation"); }
            }
            case "mana_color" -> {
                if (hasUnboundManaColor()) { throw new UnsupportedOperationException("Unbound teacher mana color"); }
            }
            case "mana_payment" -> {
                if (hasNestedManaPayment()) { throw new UnsupportedOperationException("Nested teacher mana payment"); }
            }
            default -> {
                if (hasUnfinishedAction()) {
                    throw new IllegalStateException("Previous teacher decision is still active");
                }
            }
        }
    }

    private boolean hasUnfinishedAction() {
        return activeKind != null || pendingPriority != null || pendingMana != null
                || activeMana != null || activePriority != null;
    }

    private boolean hasUnboundManaColor() {
        return activeMana == null || activeKind != null;
    }

    private boolean hasNestedManaPayment() {
        return payment != null || activeKind != null || activeMana != null || pendingPriority != null || pendingMana != null;
    }

    private boolean bindsBeforeObservation(String name) {
        return isStartupDecision(name) || isSelectionDecision(name) || name.equals("confirm");
    }

    private boolean isSelectionDecision(String name) {
        return name.equals("single_entity") || name.equals("cards") || name.equals("entities") || name.equals("discard_cleanup");
    }

    private boolean isStartupDecision(String kind) {
        return kind.equals("starting_player") || kind.equals("mulligan_keep") || kind.equals("mulligan_tuck");
    }

    private boolean isNestedSingleSelection(SpellAbility ability, String kind) {
        return activeAbility == ability && kind.equals("single_entity")
                && (activeKind.equals("cards") || activeKind.equals("entities"));
    }

    @Override public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message,
            List<String> options, Card cardToShow, Map<String, Object> params) {
        return recordLegacy(sa, "confirm", () -> new ConfirmActionRequest(player, observation, sa, mode, message,
                options, cardToShow, params),
                () -> super.confirmAction(sa, mode, message, options, cardToShow, params), new ConfirmActionRecorder());
    }

    @Override public CardCollectionView chooseCardsToDiscardToMaximumHandSize(int numDiscard) {
        return recordLegacy(null, "discard_cleanup", () -> CleanupRequests.create(player, observation, numDiscard),
                () -> super.chooseCardsToDiscardToMaximumHandSize(numDiscard), new SelectionRecorder<Card>()::encode);
    }

    @Override public void declareAttackers(Player attacker, Combat combat) {
        recordLegacy("declare_attackers", () -> new AttackersRequest(player, observation, attacker, combat),
                () -> { super.declareAttackers(attacker, combat); return null; }, new AttackersRecorder());
    }

    @Override public void declareBlockers(Player defender, Combat combat) {
        recordLegacy("declare_blockers", () -> new BlockersRequest(player, observation, defender, combat),
                () -> { super.declareBlockers(defender, combat); return null; }, new BlockersRecorder());
    }

    @Override public Map<Card, Integer> assignCombatDamage(Card source, CardCollectionView targets, CardCollectionView remaining,
            int damage, GameEntity defender, boolean overrideOrder) {
        return recordLegacy("combat_damage",
                () -> new CombatDamageRequest(player, observation, source, targets, remaining, damage, defender, overrideOrder),
                () -> super.assignCombatDamage(source, targets, remaining, damage, defender, overrideOrder),
                new CombatDamageRecorder());
    }

    @Override public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone, SpellAbility source) {
        return recordLegacy("combat_graveyard_order", () -> new CombatGraveyardRequest(player, observation, cards, destinationZone, source),
                () -> super.orderMoveToZoneList(cards, destinationZone, source), new CombatGraveyardRecorder());
    }

    @Override public List<SpellAbility> chooseSpellAbilityToPlay() {
        requireHealthy();
        return withLegacyDecision(() -> {
            try {
                requireDecisionContext("priority");
                startObservation();
                if (ManaRequest.hasSource(player)) {
                    return recordLegacy("priority_mana", () -> new ManaRequest(player, observation),
                            () -> super.chooseSpellAbilityToPlay(), (request, chosen) -> {
                                List<Integer> answer = new ManaRecorder().encode(request, chosen);
                                pendingMana = chosen == null ? null : request.selected();
                                return answer;
                            });
                }
                return recordLegacy("priority", () -> new PriorityRequest(player, observation),
                        () -> super.chooseSpellAbilityToPlay(), (request, chosen) -> {
                            List<Integer> answer = new PriorityRecorder().encode(request, chosen);
                            pendingPriority = request.bindChoice(answer);
                            return answer;
                        });
            } catch (RuntimeException error) { failure = error; throw error; }
        });
    }

    @Override public void orderAndPlaySimultaneousSa(List<SpellAbility> abilities) {
        recordLegacy("trigger_prepare", () -> {
            activeTrigger = new TriggerRequest(player, observation, abilities);
            return activeTrigger;
        }, () -> { super.orderAndPlaySimultaneousSa(abilities); return null; }, new TriggerRecorder());
    }

    private boolean hasActiveDecision() {
        return hasUnfinishedAction() || payment != null || activeTrigger != null;
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
        return recordLegacy("mana_color", () -> activeMana.colorRequest(player, observation, message, ability, colors),
                () -> super.chooseColor(message, ability, colors), new ManaColorRecorder());
    }

    @Override public boolean payManaCost(ManaCost cost, CostPartMana part, SpellAbility ability,
            String prompt, ManaConversionMatrix matrix, boolean effect) {
        requireHealthy();
        if (isBoundZeroPayment(cost, ability)) {
            try { return super.payManaCost(cost, part, ability, prompt, matrix, effect); }
            catch (RuntimeException error) { failure = error; throw error; }
        }
        return recordLegacy("mana_payment", () -> {
            payment = new ManaPaymentRequest(player, observation, cost, part, ability, matrix, effect);
            return payment;
        }, () -> super.payManaCost(cost, part, ability, prompt, matrix, effect), new ManaPaymentRecorder());
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
        return recordLegacy(sa, "single_entity", () -> new SelectionRequest<>(player, observation, "single_entity", options, title, 1, 1, optional, reveal, related, params),
                () -> super.chooseSingleEntityForEffect(options, reveal, sa, title, optional, related, params),
                (request, answer) -> new SelectionRecorder<T>().encode(request, answer == null ? List.of() : List.of(answer)));
    }

    @Override public CardCollectionView chooseCardsForEffect(CardCollectionView options, SpellAbility sa,
            String title, int min, int max, boolean optional, Map<String, Object> params) {
        return recordLegacy(sa, "cards", () -> new SelectionRequest<>(player, observation, "cards", options, title, min, max, optional, null, null, params),
                () -> super.chooseCardsForEffect(options, sa, title, min, max, optional, params), new SelectionRecorder<Card>()::encode);
    }

    @Override public <T extends GameEntity> List<T> chooseEntitiesForEffect(FCollectionView<T> options, int min, int max,
            DelayedReveal reveal, SpellAbility sa, String title, Player related, Map<String, Object> params) {
        return recordLegacy(sa, "entities", () -> new SelectionRequest<>(player, observation, "entities", options, title, min, max, min == 0, reveal, related, params),
                () -> super.chooseEntitiesForEffect(options, min, max, reveal, sa, title, related, params), new SelectionRecorder<T>()::encode);
    }
}
