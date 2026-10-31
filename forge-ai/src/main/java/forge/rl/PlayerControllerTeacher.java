package forge.rl;

import forge.rl.decisions.PriorityDecision;
import forge.rl.decisions.AttackersDecision;
import forge.rl.decisions.BlockersDecision;
import forge.rl.decisions.CombatDamageDecision;
import forge.rl.decisions.CombatGraveyardDecision;
import forge.rl.decisions.CleanupDecision;
import forge.rl.decisions.DecisionRequest;
import forge.rl.decisions.ManaDecision;
import forge.rl.decisions.ManaPaymentDecision;
import forge.rl.decisions.MulliganDecision;
import forge.rl.decisions.StartingPlayerDecision;

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
import java.util.function.Supplier;

/** Records supported final decisions, including subordinate mana colors, through the public API. */
public final class PlayerControllerTeacher extends PlayerControllerAi {
    private final IDecisionRecordCallback callback;
    private SpellAbility activeAbility;
    private String activeKind;
    private final StateReader observation;
    private boolean observationStarted;
    private RuntimeException failure;
    private PriorityDecision.Choice pendingPriority;
    private PriorityDecision.Choice activePriority;
    private ManaDecision.Choice pendingMana;
    private ManaDecision.Choice activeMana;
    private ManaPaymentDecision payment;

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
            if (activeKind != null || pendingPriority != null || pendingMana != null || activeMana != null || activePriority != null) {
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

    @Override public void declareAttackers(Player attacker, Combat combat) {
        if (failure != null) { throw failure; }
        try {
            if (activeKind != null || pendingPriority != null || pendingMana != null || activeMana != null || activePriority != null) {
                throw new IllegalStateException("Previous teacher decision is still active");
            }
            startObservation();
            var request = new AttackersDecision(player, observation, attacker, combat);
            activeKind = "declare_attackers";
            callback.before(request.data);
            super.declareAttackers(attacker, combat);
            if (failure != null) { throw failure; }
            callback.after(request.encode());
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
            var request = new BlockersDecision(player, observation, defender, combat);
            activeKind = "declare_blockers";
            callback.before(request.data);
            super.declareBlockers(defender, combat);
            if (failure != null) { throw failure; }
            callback.after(request.encode());
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
            var request = new CombatDamageDecision(player, observation, source, targets, remaining, damage, defender, overrideOrder);
            activeKind = "combat_damage";
            callback.before(request.data);
            var chosen = super.assignCombatDamage(source, targets, remaining, damage, defender, overrideOrder);
            if (failure != null) { throw failure; }
            callback.after(request.encode(chosen));
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
            var request = new CombatGraveyardDecision(player, observation, cards, destinationZone, source);
            activeKind = "combat_graveyard_order";
            callback.before(request.data);
            var chosen = super.orderMoveToZoneList(cards, destinationZone, source);
            if (failure != null) { throw failure; }
            callback.after(request.encode(chosen));
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
            PriorityDecision request = new PriorityDecision(player, observation);
            activeKind = "priority";
            callback.before(request.data);
            List<SpellAbility> chosen = super.chooseSpellAbilityToPlay();
            if (failure != null) { throw failure; }
            List<Integer> answer = request.encode(chosen);
            pendingPriority = request.apply(answer);
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
            callback.after(activeMana.encodeColor(chosen));
            return chosen;
        } catch (RuntimeException error) { failure = error; throw error; }
        finally { activeKind = null; }
    }

    @Override public boolean payManaCost(ManaCost cost, CostPartMana part, SpellAbility ability,
            String prompt, ManaConversionMatrix matrix, boolean effect) {
        if (failure != null) { throw failure; }
        if (cost.isZero() && (activePriority != null && activePriority.isMana() && activePriority.ability == ability
                || activeMana != null && activeMana.ability == ability
                || payment != null && payment.isSource(ability))) {
            try { return super.payManaCost(cost, part, ability, prompt, matrix, effect); }
            catch (RuntimeException error) { failure = error; throw error; }
        }
        try {
            if (payment != null || activeKind != null || activeMana != null || pendingPriority != null || pendingMana != null) {
                throw new UnsupportedOperationException("Nested teacher mana payment");
            }
            startObservation();
            var request = new ManaPaymentDecision(player, observation, cost, part, ability, matrix, effect);
            payment = request;
            callback.before(request.data);
            boolean paid = super.payManaCost(cost, part, ability, prompt, matrix, effect);
            if (failure != null) { throw failure; }
            callback.after(request.encode(paid));
            return paid;
        } catch (RuntimeException error) { failure = error; throw error; }
        finally { payment = null; }
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
