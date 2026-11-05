package forge.rl;

import forge.rl.requests.PriorityRequest;
import forge.rl.decisions.PriorityDecision;
import forge.rl.decisions.AttackersDecision;
import forge.rl.decisions.BlockersDecision;
import forge.rl.decisions.CombatDamageDecision;
import forge.rl.decisions.CombatGraveyardDecision;
import forge.rl.decisions.CleanupDecision;
import forge.rl.decisions.ColorDecision;
import forge.rl.decisions.ConfirmActionDecision;
import forge.rl.requests.ManaRequest;
import forge.rl.decisions.ManaDecision;
import forge.rl.decisions.ManaPaymentDecision;
import forge.rl.decisions.MulliganDecision;
import forge.rl.decisions.SelectionDecision;
import forge.rl.requests.MulliganRequests;
import forge.rl.decisions.StartingPlayerDecision;
import forge.rl.requests.TriggerRequest;
import forge.rl.decisions.TriggerDecision;
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
        return recorder.record(StartingPlayerDecision.create(isFirstGame),
                () -> super.chooseStartingPlayer(isFirstGame));
    }

    @Override public boolean mulliganKeepHand(Player first, int cardsToReturn) {
        return recorder.record(MulliganDecision.keep(first, cardsToReturn),
                () -> super.mulliganKeepHand(first, cardsToReturn));
    }

    @Override public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int cardsToReturn) {
        if (cardsToReturn == 0) {
            MulliganRequests.validateTuck(player, hand, cardsToReturn);
            return super.tuckCardsViaMulligan(hand, cardsToReturn);
        }
        return recorder.record(MulliganDecision.tuck(hand, cardsToReturn),
                () -> super.tuckCardsViaMulligan(hand, cardsToReturn));
    }

    @Override public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message,
            List<String> options, Card cardToShow, Map<String, Object> params) {
        return recorder.record(new ConfirmActionDecision(sa, mode, message, options, cardToShow, params),
                () -> super.confirmAction(sa, mode, message, options, cardToShow, params));
    }

    @Override public CardCollectionView chooseCardsToDiscardToMaximumHandSize(int numDiscard) {
        return recorder.record(new CleanupDecision(numDiscard),
                () -> super.chooseCardsToDiscardToMaximumHandSize(numDiscard));
    }

    @Override public void declareAttackers(Player attacker, Combat combat) {
        recorder.record(new AttackersDecision(attacker, combat),
                () -> { super.declareAttackers(attacker, combat); return null; });
    }

    @Override public void declareBlockers(Player defender, Combat combat) {
        recorder.record(new BlockersDecision(defender, combat),
                () -> { super.declareBlockers(defender, combat); return null; });
    }

    @Override public Map<Card, Integer> assignCombatDamage(Card source, CardCollectionView targets, CardCollectionView remaining,
            int damage, GameEntity defender, boolean overrideOrder) {
        return recorder.record(new CombatDamageDecision(source, targets, remaining, damage, defender, overrideOrder),
                () -> super.assignCombatDamage(source, targets, remaining, damage, defender, overrideOrder));
    }

    @Override public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone, SpellAbility source) {
        return recorder.record(new CombatGraveyardDecision(cards, destinationZone, source),
                () -> super.orderMoveToZoneList(cards, destinationZone, source));
    }

    @Override public List<SpellAbility> chooseSpellAbilityToPlay() {
        recorder.requireHealthy();
        try {
            if (ManaRequest.hasSource(player)) {
                return recorder.record(new ManaDecision(choice -> pendingMana = choice),
                        () -> super.chooseSpellAbilityToPlay());
            }
            return recorder.record(new PriorityDecision(choice -> pendingPriority = choice),
                    () -> super.chooseSpellAbilityToPlay());
        } catch (RuntimeException error) { recorder.fail(error); throw error; }
    }

    @Override public void orderAndPlaySimultaneousSa(List<SpellAbility> abilities) {
        recorder.record(new TriggerDecision(abilities, request -> activeTrigger = request), () -> {
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
                recorder.recordExecuted(view -> activePriority.result(player, view, reported));
            }
            return reported;
        } catch (RuntimeException error) { recorder.fail(error); throw error; }
        finally { activePriority = null; }
    }

    @Override public byte chooseColor(String message, SpellAbility ability, ColorSet colors) {
        if (activeMana == null) { throw new UnsupportedOperationException("Unbound teacher mana color"); }
        return recorder.record(new ColorDecision(activeMana, message, ability, colors),
                () -> super.chooseColor(message, ability, colors));
    }

    @Override public boolean payManaCost(ManaCost cost, CostPartMana part, SpellAbility ability,
            String prompt, ManaConversionMatrix matrix, boolean effect) {
        recorder.requireHealthy();
        return recorder.record(new ManaPaymentDecision(cost, part, ability, matrix, effect),
                () -> super.payManaCost(cost, part, ability, prompt, matrix, effect));
    }

    @Override public <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> options, DelayedReveal reveal,
            SpellAbility sa, String title, boolean optional, Player related, Map<String, Object> params) {
        return recorder.record(SelectionDecision.singleEntity(options, sa, title, optional, reveal, related, params),
                () -> super.chooseSingleEntityForEffect(options, reveal, sa, title, optional, related, params));
    }

    @Override public CardCollectionView chooseCardsForEffect(CardCollectionView options, SpellAbility sa,
            String title, int min, int max, boolean optional, Map<String, Object> params) {
        return recorder.record(SelectionDecision.cards(options, sa, title, min, max, optional, params),
                () -> super.chooseCardsForEffect(options, sa, title, min, max, optional, params));
    }

    @Override public <T extends GameEntity> List<T> chooseEntitiesForEffect(FCollectionView<T> options, int min, int max,
            DelayedReveal reveal, SpellAbility sa, String title, Player related, Map<String, Object> params) {
        return recorder.record(SelectionDecision.entities(options, sa, title, min, max, reveal, related, params),
                () -> super.chooseEntitiesForEffect(options, min, max, reveal, sa, title, related, params));
    }
}
