package forge.rl;

import forge.rl.decision.context.PriorityContext;
import forge.rl.decision.context.AttackersContext;
import forge.rl.decision.context.BlockersContext;
import forge.rl.decision.context.CombatDamageContext;
import forge.rl.decision.context.CombatGraveyardContext;
import forge.rl.decision.context.CleanupContexts;
import forge.rl.decision.context.SelectionContext;
import forge.rl.decision.context.ConfirmActionContext;
import forge.rl.decision.context.ConfirmActionExecutor;
import forge.rl.decision.context.ManaContext;
import forge.rl.decision.context.ManaPaymentContext;
import forge.rl.decision.context.MulliganContexts;
import forge.rl.decision.context.StartingPlayerContext;
import forge.rl.decision.context.TriggerContext;
import forge.rl.decision.context.SelectionExecutor;
import forge.rl.decision.context.TriggerExecutor;
import forge.rl.decision.context.CombatDamageExecutor;
import forge.rl.decision.context.PriorityTargetExecutor;
import forge.rl.decision.context.AttackersExecutor;
import forge.rl.decision.context.BlockersExecutor;
import forge.rl.decision.context.CombatGraveyardExecutor;
import forge.rl.decision.context.StartingPlayerExecutor;
import forge.rl.decision.context.MulliganKeepExecutor;
import forge.rl.decision.context.ManaExecutor;
import forge.rl.decision.context.PriorityExecutor;
import forge.rl.decision.context.ManaColorExecutor;
import forge.rl.decision.context.ManaPaymentExecutor;

import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimap;
import forge.LobbyPlayer;
import forge.card.ColorSet;
import forge.card.ICardFace;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.*;
import forge.game.ability.AbilityUtils;
import forge.game.ability.effects.RollDiceEffect;
import forge.game.card.*;
import forge.game.combat.Combat;
import forge.game.cost.*;
import forge.game.keyword.KeywordInterface;
import forge.game.mana.Mana;
import forge.game.mana.ManaConversionMatrix;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.*;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.*;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.PlayerZone;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.util.ITriggerEvent;
import forge.util.collect.FCollectionView;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import java.util.ArrayList;
import java.util.Objects;
import forge.game.ability.ApiType;
import forge.game.ability.effects.CharmEffect;

/** Independent client controller. Unsupported requests terminate this session. */
public final class PlayerControllerRl extends PlayerController {
    private final IPlayerDecisionCallback callback;
    private boolean observationStarted;
    private boolean confirming;
    private boolean callbackActive;
    private RuntimeException failure;
    private Prepared active;
    private PriorityContext.Choice pendingPriority;
    private PriorityContext.Choice activePriority;
    private ManaContext.Choice pendingMana;
    private ManaContext.Choice activeMana;
    private ManaPaymentContext payment;
    private SpellAbility paymentRoot;
    private record Prepared(SpellAbility root, AbilitySub mode, SpellAbility target) { }

    private List<Integer> answer(Map<String, Object> question) {
        checkActive();
        try {
            startObservation();
            try (var view = new StateReader(player, 0L)) {
                return response(Map.of("state", view.snapshot(), "question", question));
            }
        } catch (RuntimeException error) { throw stop(error); }
    }

    private List<Integer> response(Map<String, Object> request) {
        checkActive();
        if (callbackActive) { throw stop(new IllegalStateException("Nested client response")); }
        callbackActive = true;
        try {
            List<Integer> received = callback.choose(request);
            checkActive();
            List<Integer> values = List.copyOf(Objects.requireNonNull(received, "Missing client response"));
            for (Object value : values) {
                if (!(value instanceof Integer)) { throw new IllegalArgumentException("Client response must contain integers"); }
            }
            return values;
        } catch (RuntimeException error) { throw stop(error); }
        finally { callbackActive = false; }
    }

    private int single(Map<String, Object> request, int min, int max) {
        List<Integer> values = answer(request);
        if (values.size() != 1 || values.get(0) < min || values.get(0) > max) {
            throw stop(new IllegalArgumentException("Invalid client response for " + request.get("kind")));
        }
        return values.get(0);
    }

    private <T extends GameEntity> List<T> select(String kind, Iterable<T> options, String title,
            int min, int max, boolean optional, DelayedReveal reveal, Player related, Map<String, Object> params) {
        checkActive();
        try {
            if (hasUnfinishedDecision()) {
                throw new IllegalStateException("Previous choice has not been executed");
            }
            startObservation();
            SelectionContext<T> request = new SelectionContext<>(player, kind, options, title, min, max, optional, reveal, related, params);
            return new SelectionExecutor<>(request, response(request.encodeQuestion())).apply();
        } catch (RuntimeException error) { throw stop(error); }
    }

    private boolean binary(String kind, String prompt, String choice) {
        return single(Map.of("kind", kind, "prompt", Objects.toString(prompt, ""), "choice", choice), 0, 1) == 1;
    }

    private void requireActive(SpellAbility ability) {
        checkActive();
        if ((active == null || active.root() != ability) && (activeMana == null || activeMana.ability != ability)
                && (activePriority == null || activePriority.ability != ability)) {
            throw unsupported("unbound spell execution");
        }
    }


    public PlayerControllerRl(Game game, Player player, LobbyPlayer lobby, IPlayerDecisionCallback callback) {
        super(game, player, lobby);
        this.callback = Objects.requireNonNull(callback);
    }

    public void startObservation() {
        if (failure != null) { throw failure; }
        if (!observationStarted) {
            if (callbackActive) { throw stop(new IllegalStateException("Nested client observation")); }
            callbackActive = true;
            try (var view = new StateReader(player, 0L)) {
                callback.observe(view);
                checkActive();
                observationStarted = true;
            } catch (RuntimeException error) { throw stop(error); }
            finally { callbackActive = false; }
        }
    }

    public void closeObservation() {
        try { callback.close(); }
        finally {
            if (failure == null) { failure = new IllegalStateException("Controller observation is closed"); }
        }
    }

    private void checkActive() {
        if (failure != null) { throw failure; }
    }

    private RuntimeException stop(RuntimeException error) {
        if (failure == null) { failure = error; }
        return failure;
    }

    private RuntimeException unsupported(String method) {
        return stop(new UnsupportedOperationException("PlayerControllerRl: unsupported " + method));
    }

    @Override public SpellAbility getAbilityToPlay(Card hostCard, List<SpellAbility> abilities, ITriggerEvent triggerEvent) {
        checkActive();
        SpellAbility bound = activePriority != null ? activePriority.ability
                : activeMana != null ? activeMana.ability : active == null ? null : active.root();
        if (bound == null || abilities.size() != 1 || abilities.get(0) != bound) {
            throw unsupported("getAbilityToPlay alternatives");
        }
        return bound;
    }
    @Override public void playSpellAbilityNoStack(SpellAbility effectSA, boolean mayChoseNewTargets) {
        checkActive();
        try {
            TriggerContext.requireResolution(player, effectSA, mayChoseNewTargets);
            AbilityUtils.resolve(effectSA);
        }
        catch (RuntimeException error) { throw stop(error); }
    }
    @Override public List<SpellAbility> orderSimultaneousSa(List<SpellAbility> activePlayerSAs) { throw unsupported("orderSimultaneousSa"); }
    @Override public void orderAndPlaySimultaneousSa(List<SpellAbility> activePlayerSAs) {
        checkActive();
        try {
            startObservation();
            var request = new TriggerContext(player, activePlayerSAs);
            new TriggerExecutor(request, response(request.encodeQuestion())).apply();
        } catch (RuntimeException error) { throw stop(error); }
    }
    @Override public boolean playTrigger(Card host, WrappedAbility wrapperAbility, boolean isMandatory) { throw unsupported("playTrigger"); }
    @Override public boolean playSaFromPlayEffect(SpellAbility tgtSA) {
        checkActive();
        if (active != null) { throw unsupported("nested spell execution"); }
        // shortcut: one modal choice and one live spell target; extend after another native proof.
        if (tgtSA.getApi() != ApiType.Charm || tgtSA.usesTargeting() || tgtSA.getSubAbility() != null
                || tgtSA.getChosenList() != null || tgtSA.isCopied() || tgtSA.isEntwine()
                || tgtSA.hasParam("Random") || tgtSA.hasParam("Chooser")
                || AbilityUtils.calculateAmount(tgtSA.getHostCard(), tgtSA.getParamOrDefault("CharmNum", "1"), tgtSA) != 1
                || AbilityUtils.calculateAmount(tgtSA.getHostCard(), tgtSA.getParamOrDefault("MinCharmNum", "1"), tgtSA) != 1
                || tgtSA.getActivatingPlayer() != player) {
            throw unsupported("playSaFromPlayEffect shape");
        }
        Card source = tgtSA.getHostCard();
        try (var view = new StateReader(player, 0L)) {
            if (source.getGame() != getGame() || !view.canIdentify(source.getView())) {
                throw unsupported("unobservable or foreign modal source");
            }
            List<AbilitySub> modes = CharmEffect.makePossibleOptions(tgtSA);
            List<SpellAbility> targets = new ArrayList<>();
            getGame().getStack().forEach(item -> targets.add(item.getSpellAbility()));
            List<Integer> values = answer(Map.of("kind", "mode_target", "optional", true,
                    "source", view.reference(source),
                    "modes", modes.stream().map(mode -> mode.getParamOrDefault("SpellDescription", "")).toList(),
                    "targets", targets.stream().map(target -> Map.of("name", target.getHostCard().isFaceDown()
                            ? "Face-down spell" : target.getHostCard().getName(), "key", view.reference(target))).toList()));
            if (values.isEmpty()) {
                return false;
            }
            if (values.size() != 2 || values.get(0) < 0 || values.get(0) >= modes.size()
                    || values.get(1) < 0 || values.get(1) >= targets.size()) {
                throw stop(new IllegalArgumentException("Invalid mode/target indices"));
            }
            AbilitySub mode = modes.get(values.get(0));
            SpellAbility target = targets.get(values.get(1));
            if (mode.getSubAbility() != null || !mode.usesTargeting() || mode.getMinTargets() != 1
                    || mode.getMaxTargets() != 1 || mode.hasParam("TargetingPlayer") || mode.isDividedAsYouChoose()) {
                throw unsupported("mode/target shape");
            }
            if (!mode.canTarget(target) || getGame().getStack().getInstanceMatchingSpellAbilityID(target) == null) {
                throw stop(new IllegalArgumentException("Illegal or stale stack target"));
            }
            active = new Prepared(tgtSA, mode, target);
            try {
                if (!PlaySpellAbility.playSpellAbility(this, player, tgtSA)) {
                    throw new IllegalStateException("Native execution rejected the client response");
                }
                return true;
            } catch (RuntimeException error) {
                throw stop(error);
            } finally {
                active = null;
            }
        }

    }
    @Override public List<PaperCard> sideboard(final Deck deck, GameType gameType, String message) { throw unsupported("sideboard"); }
    @Override public List<PaperCard> chooseCardsYouWonToAddToDeck(List<PaperCard> losses) { throw unsupported("chooseCardsYouWonToAddToDeck"); }
    @Override public Map<Card, Integer> assignCombatDamage(Card source, CardCollectionView targets, CardCollectionView remaining,
            int damage, GameEntity defender, boolean overrideOrder) {
        checkActive();
        try {
            startObservation();
            var request = new CombatDamageContext(player, source, targets, remaining, damage, defender, overrideOrder);
            return new CombatDamageExecutor(request, response(request.encodeQuestion())).apply();
        } catch (RuntimeException error) { throw stop(error); }
    }
    @Override public Map<GameEntity, Integer> divideShield(Card effectSource, Map<GameEntity, Integer> affected, int shieldAmount) { throw unsupported("divideShield"); }
    @Override public Map<Byte, Integer> specifyManaCombo(SpellAbility sa, ColorSet colorSet, int manaAmount, boolean different) { throw unsupported("specifyManaCombo"); }
    @Override public CardCollectionView choosePermanentsToSacrifice(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message) { throw unsupported("choosePermanentsToSacrifice"); }
    @Override public CardCollectionView choosePermanentsToDestroy(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message) { throw unsupported("choosePermanentsToDestroy"); }
    @Override public Integer announceRequirements(SpellAbility ability, int min, int max, String announce) { throw unsupported("announceRequirements"); }
    @Override public TargetChoices chooseNewTargetsFor(SpellAbility ability, Predicate<GameObject> filter, boolean optional) { throw unsupported("chooseNewTargetsFor"); }
    @Override public boolean chooseTargetsFor(SpellAbility currentAbility) {
        checkActive();
        if (activePriority != null) {
            try {
                // Observe Hand→Stack before native targeting cancellation silently rolls it back.
                try (var view = new StateReader(player, 0L)) {
                    view.snapshot();
                }
                return new PriorityTargetExecutor(activePriority, player, currentAbility).apply();
            }
            catch (RuntimeException error) { throw stop(error); }
        }
        if (active == null || currentAbility != active.root().getSubAbility()
                || currentAbility.getSubAbility() != null || !currentAbility.getTargets().isEmpty()) {
            throw unsupported("unbound targets");
        }
        if (getGame().getStack().getInstanceMatchingSpellAbilityID(active.target()) == null
                || !currentAbility.canTarget(active.target())) {
            throw stop(new IllegalArgumentException("Selected target is no longer legal"));
        }
        currentAbility.getTargets().add(active.target());
        return true;
    }
    @Override public Pair<SpellAbilityStackInstance, GameObject> chooseTarget(SpellAbility sa, List<Pair<SpellAbilityStackInstance, GameObject>> allTargets) { throw unsupported("chooseTarget"); }
    @Override public boolean helpPayForAssistSpell(ManaCostBeingPaid cost, SpellAbility sa, int max, int requested) { throw unsupported("helpPayForAssistSpell"); }
    @Override public Player choosePlayerToAssistPayment(FCollectionView<Player> optionList, SpellAbility sa, String title, int max) { throw unsupported("choosePlayerToAssistPayment"); }
    @Override public CardCollectionView chooseCardsForEffect(CardCollectionView sourceList, SpellAbility sa, String title, int min, int max, boolean isOptional, Map<String, Object> params) {
        return new CardCollection(select("cards", sourceList, title, min, max, isOptional, null, null, params));
    }
    @Override public CardCollection chooseCardsForEffectMultiple(Map<String, CardCollection> validMap, SpellAbility sa, String title, boolean isOptional) { throw unsupported("chooseCardsForEffectMultiple"); }
    @Override public <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> optionList, DelayedReveal delayedReveal, SpellAbility sa, String title, boolean isOptional, Player relatedPlayer, Map<String, Object> params) {
        List<T> chosen = select("single_entity", optionList, title, 1, 1, isOptional, delayedReveal, relatedPlayer, params);
        return chosen.isEmpty() ? null : chosen.get(0);
    }
    @Override public <T extends GameEntity> List<T> chooseEntitiesForEffect(FCollectionView<T> optionList, int min, int max, DelayedReveal delayedReveal, SpellAbility sa, String title, Player relatedPlayer, Map<String, Object> params) {
        return select("entities", optionList, title, min, max, min == 0, delayedReveal, relatedPlayer, params);
    }
    @Override public List<SpellAbility> chooseSpellAbilitiesForEffect(List<SpellAbility> spells, SpellAbility sa, String title, int num, Map<String, Object> params) { throw unsupported("chooseSpellAbilitiesForEffect"); }
    @Override public SpellAbility chooseSingleSpellForEffect(List<SpellAbility> spells, SpellAbility sa, String title, Map<String, Object> params) { throw unsupported("chooseSingleSpellForEffect"); }
    @Override public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message, List<String> options, Card cardToShow, Map<String, Object> params) {
        checkActive();
        try {
            if (cannotStartConfirmation()) { throw new IllegalStateException("Previous choice has not been executed"); }
            confirming = true;
            startObservation();
            var request = new ConfirmActionContext(player, sa, mode, message, options, cardToShow, params);
            return new ConfirmActionExecutor(request, response(request.encodeQuestion())).apply();
        } catch (RuntimeException error) { throw stop(error); }
        finally { confirming = false; }
    }

    private boolean cannotStartConfirmation() {
        return hasUnfinishedDecision() || active != null || callbackActive;
    }
    @Override public boolean confirmBidAction(SpellAbility sa, PlayerActionConfirmMode bidlife, String string, int bid, Player winner) { throw unsupported("confirmBidAction"); }
    @Override public boolean confirmReplacementEffect(ReplacementEffect replacementEffect, SpellAbility effectSA, GameEntity affected, String question) { throw unsupported("confirmReplacementEffect"); }
    @Override public boolean confirmStaticApplication(Card hostCard, PlayerActionConfirmMode mode, String message, String logic) { throw unsupported("confirmStaticApplication"); }
    @Override public boolean confirmTrigger(WrappedAbility sa) { throw unsupported("confirmTrigger"); }
    @Override public List<Card> exertAttackers(List<Card> attackers) { throw unsupported("exertAttackers"); }
    @Override public List<Card> enlistAttackers(List<Card> attackers) { throw unsupported("enlistAttackers"); }
    @Override public void declareAttackers(Player attacker, Combat combat) {
        checkActive();
        try {
            startObservation();
            var request = new AttackersContext(player, attacker, combat);
            new AttackersExecutor(request, response(request.encodeQuestion())).apply();
        } catch (RuntimeException error) { throw stop(error); }
    }
    @Override public void declareBlockers(Player defender, Combat combat) {
        checkActive();
        try {
            startObservation();
            var request = new BlockersContext(player, defender, combat);
            new BlockersExecutor(request, response(request.encodeQuestion())).apply();
        } catch (RuntimeException error) { throw stop(error); }
    }
    @Override public CardCollection orderBlockers(Card attacker, CardCollection blockers) { throw unsupported("orderBlockers"); }
    @Override public CardCollection orderBlocker(final Card attacker, final Card blocker, final CardCollection oldBlockers) { throw unsupported("orderBlocker"); }
    @Override public CardCollection orderAttackers(Card blocker, CardCollection attackers) { throw unsupported("orderAttackers"); }
    @Override public void reveal(CardCollectionView cards, ZoneType zone, Player owner, String messagePrefix, boolean addMsgSuffix) { throw unsupported("reveal"); }
    @Override public void reveal(List<CardView> cards, ZoneType zone, PlayerView owner, String messagePrefix, boolean addMsgSuffix) { throw unsupported("reveal"); }
    @Override public void notifyOfValue(SpellAbility saSource, GameObject realtedTarget, String value) {
        checkActive();
        // Mana announcements ask no question; the resulting pool is read from native state.
        if (saSource == null || !ManaContext.supported(saSource)) { return; }
        try (var view = new StateReader(player, 0L)) {
            if (!view.canIdentify(saSource.getHostCard().getView())) { throw unsupported("notifyOfValue"); }
        }
    }
    @Override public ImmutablePair<CardCollection, CardCollection> arrangeForScry(CardCollection topN) { throw unsupported("arrangeForScry"); }
    @Override public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN) { throw unsupported("arrangeForSurveil"); }
    @Override public boolean willPutCardOnTop(Card c) { throw unsupported("willPutCardOnTop"); }
    @Override public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone, SpellAbility source) {
        checkActive();
        try {
            startObservation();
            var request = new CombatGraveyardContext(player, cards, destinationZone, source);
            return new CombatGraveyardExecutor(request, response(request.encodeQuestion())).apply();
        } catch (RuntimeException error) { throw stop(error); }
    }
    @Override public CardCollectionView chooseCardsToDiscardFrom(Player playerDiscard, SpellAbility sa, CardCollection validCards, int min, int max, CardCollectionView visibleToChooser) { throw unsupported("chooseCardsToDiscardFrom"); }
    @Override public CardCollectionView chooseCardsToDiscardUnlessType(int min, CardCollectionView hand, String[] unlessTypes, SpellAbility sa) { throw unsupported("chooseCardsToDiscardUnlessType"); }
    @Override public CardCollectionView chooseCardsToDiscardToMaximumHandSize(int numDiscard) {
        checkActive();
        try {
            if (hasUnfinishedDecision()) {
                throw new IllegalStateException("Previous choice has not been executed");
            }
            startObservation();
            var request = CleanupContexts.create(player, numDiscard);
            return new CardCollection(new SelectionExecutor<>(request, response(request.encodeQuestion())).apply());
        } catch (RuntimeException error) { throw stop(error); }
    }
    @Override public CardCollectionView chooseCardsToDelve(int genericAmount, CardCollection grave) { throw unsupported("chooseCardsToDelve"); }
    @Override public Map<Card, ManaCostShard> chooseCardsForConvokeOrImprovise(SpellAbility sa, ManaCost manaCost, CardCollectionView untappedCards, boolean artifacts, boolean creatures, Integer maxReduction) { throw unsupported("chooseCardsForConvokeOrImprovise"); }
    @Override public List<Card> chooseCardsForSplice(SpellAbility sa, List<Card> cards) { throw unsupported("chooseCardsForSplice"); }
    @Override public CardCollectionView chooseCardsToRevealFromHand(int min, int max, CardCollectionView valid) { throw unsupported("chooseCardsToRevealFromHand"); }
    @Override public List<SpellAbility> chooseSaToActivateFromOpeningHand(List<SpellAbility> usableFromOpeningHand) { throw unsupported("chooseSaToActivateFromOpeningHand"); }
    @Override public Player chooseStartingPlayer(boolean isFirstGame) {
        checkActive();
        try {
            if (hasUnfinishedDecision()) {
                throw new IllegalStateException("Previous choice has not been executed");
            }
            startObservation();
            var request = new StartingPlayerContext(player, isFirstGame);
            return new StartingPlayerExecutor(request, response(request.encodeQuestion())).apply();
        } catch (RuntimeException error) { throw stop(error); }
    }
    @Override public PlayerZone chooseStartingHand(List<PlayerZone> zones) { throw unsupported("chooseStartingHand"); }
    @Override public Mana chooseManaFromPool(List<Mana> manaChoices) { throw unsupported("chooseManaFromPool"); }
    @Override public String chooseSomeType(String kindOfType, SpellAbility sa, Collection<String> validTypes, boolean isOptional) {
        List<String> options = List.copyOf(validTypes);
        int index = single(Map.of("kind", "type", "prompt", kindOfType, "items", options, "optional", isOptional), isOptional ? -1 : 0, options.size() - 1);
        return index == -1 ? null : options.get(index);
    }
    @Override public String chooseSector(Card assignee, String ai, List<String> sectors) { throw unsupported("chooseSector"); }
    @Override public List<Card> chooseContraptionsToCrank(List<Card> contraptions) { throw unsupported("chooseContraptionsToCrank"); }
    @Override public int chooseSprocket(Card assignee, List<Integer> sprockets) { throw unsupported("chooseSprocket"); }
    @Override public PlanarDice choosePDRollToIgnore(List<PlanarDice> rolls) { throw unsupported("choosePDRollToIgnore"); }
    @Override public Integer chooseRollToIgnore(List<Integer> rolls) { throw unsupported("chooseRollToIgnore"); }
    @Override public List<Integer> chooseDiceToReroll(List<Integer> rolls) { throw unsupported("chooseDiceToReroll"); }
    @Override public Integer chooseRollToModify(List<Integer> rolls) { throw unsupported("chooseRollToModify"); }
    @Override public RollDiceEffect.DieRollResult chooseRollToSwap(List<RollDiceEffect.DieRollResult> rolls) { throw unsupported("chooseRollToSwap"); }
    @Override public String chooseRollSwapValue(List<String> swapChoices, Integer currentResult, int power, int toughness) { throw unsupported("chooseRollSwapValue"); }
    @Override public Object vote(SpellAbility sa, String prompt, List<Object> options, ListMultimap<Object, Player> votes, Player forPlayer, boolean optional) { throw unsupported("vote"); }
    @Override public boolean mulliganKeepHand(Player first, int cardsToReturn) {
        checkActive();
        try {
            if (hasUnfinishedDecision()) {
                throw new IllegalStateException("Previous choice has not been executed");
            }
            startObservation();
            return new MulliganKeepExecutor(response(MulliganContexts.keep(player, first, cardsToReturn).encodeQuestion())).apply();
        } catch (RuntimeException error) { throw stop(error); }
    }
    @Override public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int cardsToReturn) {
        checkActive();
        try {
            if (hasUnfinishedDecision()) {
                throw new IllegalStateException("Previous choice has not been executed");
            }
            startObservation();
            var request = MulliganContexts.tuck(player, hand, cardsToReturn);
            return new CardCollection(new SelectionExecutor<>(request, cardsToReturn == 0 ? List.of() : response(request.encodeQuestion())).apply());
        } catch (RuntimeException error) { throw stop(error); }
    }
    @Override public List<SpellAbility> chooseSpellAbilityToPlay() {
        checkActive();
        try {
            if (hasUnfinishedDecision()) {
                throw new IllegalStateException("Previous choice has not been executed");
            }
            startObservation();
            if (ManaContext.hasSource(player)) {
                ManaContext request = new ManaContext(player);
                pendingMana = new ManaExecutor(request, response(request.encodeQuestion()), player).apply();
                return pendingMana == null ? null : List.of(pendingMana.ability);
            }
            PriorityContext request = new PriorityContext(player);
            pendingPriority = new PriorityExecutor(request, response(request.encodeQuestion())).apply();
            return pendingPriority == null ? null : List.of(pendingPriority.ability);
        } catch (RuntimeException error) { throw stop(error); }
    }

    private boolean hasUnfinishedDecision() {
        return confirming || pendingPriority != null || pendingMana != null || activeMana != null || activePriority != null;
    }

    @Override public boolean playChosenSpellAbility(SpellAbility sa) {
        checkActive();
        if (pendingMana != null) {
            activeMana = pendingMana;
            pendingMana = null;
            try {
                activeMana.before(player, sa);
                boolean reported = PlaySpellAbility.playSpellAbility(this, player, sa);
                activeMana.after(player, reported);
                return reported;
            } catch (RuntimeException error) { throw stop(error); }
            finally { activeMana = null; }
        }
        activePriority = pendingPriority;
        pendingPriority = null;
        try {
            if (activePriority == null) { throw new IllegalArgumentException("Unbound priority action"); }
            activePriority.before(player, sa);
            boolean reported = PlaySpellAbility.playSpellAbility(this, player, sa);
            activePriority.after(player, reported);
            if (activePriority.isCast()) {
                try (var view = new StateReader(player, 0L)) {
                    callback.executed(activePriority.result(player, view, reported));
                }
            }
            return reported;
        } catch (RuntimeException error) { throw stop(error); }
        finally { activePriority = null; }
    }
    @Override public List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> possible, int min, int num, boolean allowRepeat) {
        requireActive(sa);
        if (active == null || min != 1 || num != 1 || !possible.contains(active.mode())) { throw unsupported("changed modes"); }
        List<AbilitySub> chosen = new ArrayList<>(List.of(active.mode()));
        sa.setChosenList(chosen);
        return chosen;
    }
    @Override public int chooseNumberForCostReduction(final SpellAbility sa, final int min, final int max) { throw unsupported("chooseNumberForCostReduction"); }
    @Override public int chooseNumberForKeywordCost(SpellAbility sa, Cost cost, KeywordInterface keyword, String prompt, int max) { throw unsupported("chooseNumberForKeywordCost"); }
    @Override public int chooseNumber(SpellAbility sa, String title, int min, int max) {
        return single(Map.of("kind", "number", "prompt", Objects.toString(title, ""), "min", min, "max", max), min, max);
    }
    @Override public int chooseNumber(SpellAbility sa, String title, List<Integer> values, Player relatedPlayer) {
        List<Integer> options = List.copyOf(values);
        int index = single(Map.of("kind", "number_option", "prompt", Objects.toString(title, ""), "items", options), 0, options.size() - 1);
        return options.get(index);
    }
    @Override public boolean chooseBinary(SpellAbility sa, String question, BinaryChoiceType kindOfChoice, Boolean defaultChoice) {
        return binary("binary", question, kindOfChoice.name());
    }
    @Override public boolean chooseFlipResult(SpellAbility sa, Player flipper, boolean call) { throw unsupported("chooseFlipResult"); }
    @Override public byte chooseColor(String message, SpellAbility sa, ColorSet colors) {
        checkActive();
        try {
            if (activeMana == null) { throw unsupported("unbound mana color"); }
            var request = activeMana.colorRequest(player, message, sa, colors);
            return new ManaColorExecutor(activeMana, response(request.encodeQuestion())).apply();
        } catch (RuntimeException error) { throw stop(error); }
    }
    @Override public byte chooseColorAllowColorless(String message, Card c, ColorSet colors) { throw unsupported("chooseColorAllowColorless"); }
    @Override public ColorSet chooseColors(String message, SpellAbility sa, int min, int max, ColorSet options) { throw unsupported("chooseColors"); }
    @Override public ICardFace chooseSingleCardFace(SpellAbility sa, String message, Predicate<ICardFace> cpp, String name) { throw unsupported("chooseSingleCardFace"); }
    @Override public ICardFace chooseSingleCardFace(SpellAbility sa, List<ICardFace> faces, String message) { throw unsupported("chooseSingleCardFace"); }
    @Override public CardState chooseSingleCardState(SpellAbility sa, List<CardState> states, String message, Map<String, Object> params) { throw unsupported("chooseSingleCardState"); }
    @Override public boolean chooseCardsPile(SpellAbility sa, CardCollectionView pile1, CardCollectionView pile2, String faceUp) { throw unsupported("chooseCardsPile"); }
    @Override public CounterType chooseCounterType(List<CounterType> options, SpellAbility sa, String prompt, Map<String, Object> params) { throw unsupported("chooseCounterType"); }
    @Override public String chooseKeywordForPump(List<String> options, SpellAbility sa, String prompt, Card tgtCard) { throw unsupported("chooseKeywordForPump"); }
    @Override public boolean confirmPayment(CostPart costPart, String string, SpellAbility sa) { throw unsupported("confirmPayment"); }
    @Override public ReplacementEffect chooseSingleReplacementEffect(List<ReplacementEffect> possibleReplacers) { throw unsupported("chooseSingleReplacementEffect"); }
    @Override public StaticAbility chooseSingleStaticAbility(List<StaticAbility> possibleReplacers) { throw unsupported("chooseSingleStaticAbility"); }
    @Override public String chooseProtectionType(SpellAbility sa, List<String> choices) { throw unsupported("chooseProtectionType"); }
    @Override public void revealAnte(String message, Multimap<Player, PaperCard> removedAnteCards) { throw unsupported("revealAnte"); }
    @Override public void revealAISkipCards(String message, Map<Player, Map<DeckSection, List<? extends PaperCard>>> deckCards) { throw unsupported("revealAISkipCards"); }
    @Override public void revealUnsupported(Map<Player, List<PaperCard>> unsupported) {
        checkActive();
        // Match reports an empty unsupported-card list for every seat even in an ordinary game.
        if (unsupported.values().stream().anyMatch(cards -> !cards.isEmpty())) { throw unsupported("revealUnsupported"); }
    }
    @Override public List<OptionalCostValue> chooseOptionalCosts(SpellAbility choosen, List<OptionalCostValue> optionalCostValues) { throw unsupported("chooseOptionalCosts"); }
    @Override public List<CostPart> orderCosts(List<CostPart> costs) { throw unsupported("orderCosts"); }
    @Override public boolean payCostToPreventEffect(Cost cost, SpellAbility sa, boolean alreadyPaid, FCollectionView<Player> allPayers) { throw unsupported("payCostToPreventEffect"); }
    @Override public boolean payCostDuringRoll(Cost cost, SpellAbility sa) { throw unsupported("payCostDuringRoll"); }
    @Override public boolean payCombatCost(Card card, Cost cost, SpellAbility sa, String prompt) { throw unsupported("payCombatCost"); }
    @Override public boolean payManaCost(ManaCost toPay, CostPartMana costPartMana, SpellAbility sa, String prompt, ManaConversionMatrix matrix, boolean effect) {
        checkActive();
        if (toPay.isZero() && (active != null || activeMana != null
                || activePriority != null && activePriority.isMana())) {
            requireActive(sa);
            return PlaySpellAbility.payManaCost(this, toPay, costPartMana, sa, player, prompt, matrix, effect);
        }
        try {
            if (paymentRoot != null || active != null || activeMana != null || pendingPriority != null || pendingMana != null) {
                throw unsupported("nested mana payment");
            }
            startObservation();
            payment = new ManaPaymentContext(player, toPay, costPartMana, sa, matrix, effect);
            paymentRoot = sa;
            if (toPay.isZero()) { return new ManaPaymentExecutor(payment, response(payment.encodeQuestion()), new ManaCostBeingPaid(toPay)).apply(); }
            return PlaySpellAbility.payManaCost(this, toPay, costPartMana, sa, player, prompt, matrix, effect);
        } catch (RuntimeException error) { throw stop(error); }
        finally { payment = null; paymentRoot = null; }
    }
    @Override public boolean applyManaToCost(ManaCostBeingPaid toPay, SpellAbility ability, String prompt, ManaConversionMatrix matrix, boolean effect) {
        checkActive();
        try {
            if (payment == null || paymentRoot != ability || effect) { throw unsupported("unbound mana payment"); }
            ManaPaymentContext request = payment;
            payment = null;
            return new ManaPaymentExecutor(request, response(request.encodeQuestion()), toPay).apply();
        } catch (RuntimeException error) { throw stop(error); }
    }
    @Override public CardCollectionView chooseCardsForCost(CardCollectionView optionList, SpellAbility sa, CostPartWithList cpl, int amount, boolean isOptional, String prompt) { throw unsupported("chooseCardsForCost"); }
    @Override public CostDecisionMakerBase getCostDecisionMaker(Player player, SpellAbility ability, boolean effect, String prompt) {
        requireActive(ability);
        if (player != this.player) { throw unsupported("another payer"); }
        return new Costs(player, ability, effect);
    }
    @Override public String chooseCardName(SpellAbility sa, Predicate<ICardFace> cpp, String valid, String message) { throw unsupported("chooseCardName"); }
    @Override public String chooseCardName(SpellAbility sa, List<ICardFace> faces, String message) { throw unsupported("chooseCardName"); }
    @Override public Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa, CardCollection fetchList, DelayedReveal delayedReveal, String selectPrompt, boolean isOptional, Player decider) { throw unsupported("chooseSingleCardForZoneChange"); }
    @Override public List<Card> chooseCardsForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa, CardCollection fetchList, int min, int max, DelayedReveal delayedReveal, String selectPrompt, Player decider) { throw unsupported("chooseCardsForZoneChange"); }
    @Override public void autoPassCancel() { checkActive(); }
    @Override public void awaitNextInput() {
        try { startObservation(); }
        catch (RuntimeException error) { throw stop(error); }
    }
    @Override public void cancelAwaitNextInput() { throw unsupported("cancelAwaitNextInput"); }

    private final class Costs extends CostDecisionMakerBase {
        Costs(Player payer, SpellAbility ability, boolean effect) {
            super(payer, effect, ability, ability.getHostCard());
        }
        @Override public boolean paysRightAfterDecision() { return false; }
        @Override public PaymentDecision visit(CostBehold cost) { throw unsupported("cost CostBehold"); }
        @Override public PaymentDecision visit(CostBeholdExile cost) { throw unsupported("cost CostBeholdExile"); }
        @Override public PaymentDecision visit(CostGainControl cost) { throw unsupported("cost CostGainControl"); }
        @Override public PaymentDecision visit(CostChooseColor cost) { throw unsupported("cost CostChooseColor"); }
        @Override public PaymentDecision visit(CostChooseCreatureType cost) { throw unsupported("cost CostChooseCreatureType"); }
        @Override public PaymentDecision visit(CostCollectEvidence cost) { throw unsupported("cost CostCollectEvidence"); }
        @Override public PaymentDecision visit(CostDiscard cost) { throw unsupported("cost CostDiscard"); }
        @Override public PaymentDecision visit(CostDamage cost) { throw unsupported("cost CostDamage"); }
        @Override public PaymentDecision visit(CostDraw cost) { throw unsupported("cost CostDraw"); }
        @Override public PaymentDecision visit(CostExile cost) { throw unsupported("cost CostExile"); }
        @Override public PaymentDecision visit(CostExileFromStack cost) { throw unsupported("cost CostExileFromStack"); }
        @Override public PaymentDecision visit(CostExiledMoveToGrave cost) { throw unsupported("cost CostExiledMoveToGrave"); }
        @Override public PaymentDecision visit(CostExert cost) { throw unsupported("cost CostExert"); }
        @Override public PaymentDecision visit(CostEnlist cost) { throw unsupported("cost CostEnlist"); }
        @Override public PaymentDecision visit(CostFlipCoin cost) { throw unsupported("cost CostFlipCoin"); }
        @Override public PaymentDecision visit(CostForage cost) { throw unsupported("cost CostForage"); }
        @Override public PaymentDecision visit(CostRollDice cost) { throw unsupported("cost CostRollDice"); }
        @Override public PaymentDecision visit(CostMill cost) { throw unsupported("cost CostMill"); }
        @Override public PaymentDecision visit(CostAddMana cost) { throw unsupported("cost CostAddMana"); }
        @Override public PaymentDecision visit(CostPayLife cost) { throw unsupported("cost CostPayLife"); }
        @Override public PaymentDecision visit(CostPayEnergy cost) { throw unsupported("cost CostPayEnergy"); }
        @Override public PaymentDecision visit(CostGainLife cost) { throw unsupported("cost CostGainLife"); }
        @Override public PaymentDecision visit(CostPartMana cost) { checkActive(); return PaymentDecision.number(0); }
        @Override public PaymentDecision visit(CostPromiseGift cost) { throw unsupported("cost CostPromiseGift"); }
        @Override public PaymentDecision visit(CostPutCardToLib cost) { throw unsupported("cost CostPutCardToLib"); }
        @Override public PaymentDecision visit(CostTap cost) {
            requireActive(ability);
            if ((activeMana == null || activeMana.ability != ability)
                    && (activePriority == null || !activePriority.isMana() || activePriority.ability != ability)) { throw unsupported("cost CostTap"); }
            return PaymentDecision.number(1);
        }
        @Override public PaymentDecision visit(CostSacrifice cost) { throw unsupported("cost CostSacrifice"); }
        @Override public PaymentDecision visit(CostReturn cost) { throw unsupported("cost CostReturn"); }
        @Override public PaymentDecision visit(CostReveal cost) { throw unsupported("cost CostReveal"); }
        @Override public PaymentDecision visit(CostRevealChosen cost) { throw unsupported("cost CostRevealChosen"); }
        @Override public PaymentDecision visit(CostRemoveAnyCounter cost) { throw unsupported("cost CostRemoveAnyCounter"); }
        @Override public PaymentDecision visit(CostRemoveCounter cost) { throw unsupported("cost CostRemoveCounter"); }
        @Override public PaymentDecision visit(CostPutCounter cost) { throw unsupported("cost CostPutCounter"); }
        @Override public PaymentDecision visit(CostPutCounterYou cost) { throw unsupported("cost CostPutCounterYou"); }
        @Override public PaymentDecision visit(CostUntapType cost) { throw unsupported("cost CostUntapType"); }
        @Override public PaymentDecision visit(CostUntap cost) { throw unsupported("cost CostUntap"); }
        @Override public PaymentDecision visit(CostUnattach cost) { throw unsupported("cost CostUnattach"); }
        @Override public PaymentDecision visit(CostTapType cost) { throw unsupported("cost CostTapType"); }
        @Override public PaymentDecision visit(CostPayShards cost) { throw unsupported("cost CostPayShards"); }
        @Override public PaymentDecision visit(CostBlight cost) { throw unsupported("cost CostBlight"); }
    }
}
