package forge.rl;

import forge.rl.decisions.PriorityRequest;
import forge.rl.decisions.AttackersRequest;
import forge.rl.decisions.BlockersRequest;
import forge.rl.decisions.CombatDamageRequest;
import forge.rl.decisions.CombatGraveyardRequest;
import forge.rl.decisions.CleanupRequests;
import forge.rl.decisions.SelectionRequest;
import forge.rl.decisions.ConfirmActionRequest;
import forge.rl.decisions.ManaRequest;
import forge.rl.decisions.ManaPaymentRequest;
import forge.rl.decisions.MulliganRequests;
import forge.rl.decisions.StartingPlayerRequest;
import forge.rl.decisions.TriggerRequest;
import forge.rl.decisions.GameRecorder;

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

/** Records supported final decisions, including subordinate mana colors, through the public API. */
public final class PlayerControllerTeacher extends PlayerControllerAi {
    private final GameRecorder recorder;
    private PriorityRequest.Choice pendingPriority;
    private PriorityRequest.Choice activePriority;
    private ManaRequest.Choice pendingMana;
    private ManaRequest.Choice activeMana;
    private TriggerRequest activeTrigger;

    public PlayerControllerTeacher(Game game, Player player, LobbyPlayerAi lobby, IDecisionRecordCallback callback) {
        super(game, player, lobby);
        this.recorder = new GameRecorder(player, callback);
    }

    public void closeObservation() {
        recorder.close();
    }

    @Override public void awaitNextInput() {
        try {
            super.awaitNextInput();
        } catch (RuntimeException error) {
            recorder.fail(error);
            throw error;
        }
    }

    @Override public Player chooseStartingPlayer(boolean isFirstGame) {
        return recorder.record(new StartingPlayerRequest(player, isFirstGame),
                () -> super.chooseStartingPlayer(isFirstGame));
    }

    @Override public boolean mulliganKeepHand(Player first, int cardsToReturn) {
        return recorder.record(MulliganRequests.keep(player, first, cardsToReturn),
                () -> super.mulliganKeepHand(first, cardsToReturn));
    }

    @Override public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int cardsToReturn) {
        var request = MulliganRequests.tuck(player, hand, cardsToReturn);
        if (cardsToReturn == 0) { return super.tuckCardsViaMulligan(hand, cardsToReturn); }
        return recorder.record(request, () -> super.tuckCardsViaMulligan(hand, cardsToReturn));
    }

    @Override public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message,
            List<String> options, Card cardToShow, Map<String, Object> params) {
        return recorder.record(new ConfirmActionRequest(player, sa, mode, message, options, cardToShow, params), sa,
                () -> super.confirmAction(sa, mode, message, options, cardToShow, params));
    }

    @Override public CardCollectionView chooseCardsToDiscardToMaximumHandSize(int numDiscard) {
        return recorder.record(CleanupRequests.create(player, numDiscard),
                () -> super.chooseCardsToDiscardToMaximumHandSize(numDiscard));
    }

    @Override public void declareAttackers(Player attacker, Combat combat) {
        recorder.record(new AttackersRequest(player, attacker, combat),
                () -> { super.declareAttackers(attacker, combat); return null; });
    }

    @Override public void declareBlockers(Player defender, Combat combat) {
        recorder.record(new BlockersRequest(player, defender, combat),
                () -> { super.declareBlockers(defender, combat); return null; });
    }

    @Override public Map<Card, Integer> assignCombatDamage(Card source, CardCollectionView targets, CardCollectionView remaining,
            int damage, GameEntity defender, boolean overrideOrder) {
        return recorder.record(new CombatDamageRequest(player, source, targets, remaining, damage, defender, overrideOrder),
                () -> super.assignCombatDamage(source, targets, remaining, damage, defender, overrideOrder));
    }

    @Override public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone, SpellAbility source) {
        return recorder.record(new CombatGraveyardRequest(player, cards, destinationZone, source),
                () -> super.orderMoveToZoneList(cards, destinationZone, source));
    }

    @Override public List<SpellAbility> chooseSpellAbilityToPlay() {
        recorder.requireHealthy();
        try {
            if (ManaRequest.hasSource(player)) {
                ManaRequest request = new ManaRequest(player);
                return recorder.record(request, () -> {
                    List<SpellAbility> chosen = super.chooseSpellAbilityToPlay();
                    pendingMana = chosen == null ? null : request.selected();
                    return chosen;
                });
            }
            PriorityRequest request = new PriorityRequest(player);
            return recorder.record(request, () -> {
                List<SpellAbility> chosen = super.chooseSpellAbilityToPlay();
                pendingPriority = request.bindChoice(request.encode(chosen));
                return chosen;
            });
        } catch (RuntimeException error) { recorder.fail(error); throw error; }
    }

    @Override public void orderAndPlaySimultaneousSa(List<SpellAbility> abilities) {
        TriggerRequest request = new TriggerRequest(player, abilities);
        recorder.record(request, () -> {
            activeTrigger = request;
            super.orderAndPlaySimultaneousSa(abilities);
            return null;
        });
    }

    @Override public List<SpellAbility> orderSimultaneousSa(List<SpellAbility> abilities) {
        recorder.requireHealthy();
        if (activeTrigger == null) { throw new UnsupportedOperationException("Unbound teacher trigger order"); }
        activeTrigger.requireBoundOrder(abilities);
        return super.orderSimultaneousSa(abilities);
    }

    @Override public void playSpellAbilityNoStack(SpellAbility ability, boolean setupTargets) {
        recorder.requireHealthy();
        TriggerRequest.requireResolution(player, ability, setupTargets);
        super.playSpellAbilityNoStack(ability, setupTargets);
    }

    @Override public boolean playChosenSpellAbility(SpellAbility ability) {
        recorder.requireHealthy();
        if (pendingMana != null) {
            activeMana = pendingMana;
            pendingMana = null;
            try {
                activeMana.before(player, ability);
                boolean reported = super.playChosenSpellAbility(ability);
                recorder.requireHealthy();
                activeMana.after(player, reported);
                return reported;
            } catch (RuntimeException error) { recorder.fail(error); throw error; }
            finally { activeMana = null; }
        }
        activePriority = pendingPriority;
        pendingPriority = null;
        try {
            if (activePriority == null) { throw new IllegalArgumentException("Unbound teacher priority action"); }
            activePriority.before(player, ability);
            boolean reported = super.playChosenSpellAbility(ability);
            recorder.requireHealthy();
            activePriority.after(player, reported);
            if (activePriority.isCast()) {
                try (var view = new StateReader(player, 0L)) {
                    recorder.recordExecuted(activePriority.result(player, view, reported));
                }
            }
            return reported;
        } catch (RuntimeException error) { recorder.fail(error); throw error; }
        finally { activePriority = null; }
    }

    @Override public byte chooseColor(String message, SpellAbility ability, ColorSet colors) {
        if (activeMana == null) { throw new UnsupportedOperationException("Unbound teacher mana color"); }
        return recorder.record(activeMana.colorRequest(player, message, ability, colors),
                () -> super.chooseColor(message, ability, colors));
    }

    @Override public boolean payManaCost(ManaCost cost, CostPartMana part, SpellAbility ability,
            String prompt, ManaConversionMatrix matrix, boolean effect) {
        recorder.requireHealthy();
        // Simplified zero payment logic for now
        return recorder.record(new ManaPaymentRequest(player, cost, part, ability, matrix, effect),
                () -> super.payManaCost(cost, part, ability, prompt, matrix, effect));
    }

    @Override public <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> options, DelayedReveal reveal,
            SpellAbility sa, String title, boolean optional, Player related, Map<String, Object> params) {
        return recorder.record(new SelectionRequest<>(player, "single_entity", options, title, 1, 1, optional, reveal, related, params), sa,
                () -> super.chooseSingleEntityForEffect(options, reveal, sa, title, optional, related, params));
    }

    @Override public CardCollectionView chooseCardsForEffect(CardCollectionView options, SpellAbility sa,
            String title, int min, int max, boolean optional, Map<String, Object> params) {
        return recorder.record(new SelectionRequest<>(player, "cards", options, title, min, max, optional, null, null, params), sa,
                () -> super.chooseCardsForEffect(options, sa, title, min, max, optional, params));
    }

    @Override public <T extends GameEntity> List<T> chooseEntitiesForEffect(FCollectionView<T> options, int min, int max,
            DelayedReveal reveal, SpellAbility sa, String title, Player related, Map<String, Object> params) {
        return recorder.record(new SelectionRequest<>(player, "entities", options, title, min, max, min == 0, reveal, related, params), sa,
                () -> super.chooseEntitiesForEffect(options, min, max, reveal, sa, title, related, params));
    }
}
