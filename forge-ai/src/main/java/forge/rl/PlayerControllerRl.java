package forge.rl;

import forge.LobbyPlayer;
import forge.ai.AiPlayDecision;
import forge.ai.AiCardMemory;
import forge.ai.AiCardMemory.MemorySet;
import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCost;
import forge.ai.PlayerControllerAi;
import forge.card.mana.ManaCost;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.ability.effects.CharmEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.combat.Combat;
import forge.game.cost.CostPartMana;
import forge.game.mana.ManaConversionMatrix;
import forge.game.player.Player;
import forge.game.player.DelayedReveal;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PlayerController for the RL agent.
 *
 * Supported ActionDraft roots leave costs and targets unselected for the agent.
 * Unsupported choices, combat and replacements remain delegated to Forge AI.
 */
public class PlayerControllerRl extends PlayerControllerAi {

    private final IDecisionCallback callback;
    private int rawCandidateCount = 0;
    private int filteredCandidateCount = 0;
    private int invalidTargetCandidateCount = 0;
    private int invalidModeCandidateCount = 0;
    private int manaCandidateFilteredCount = 0;
    private int automaticPassCount = 0;
    private int chosenActionCount = 0;
    private int chosenPassActionCount = 0;
    private int failedActionCount = 0;
    private int cardsBottomed = 0;
    private boolean teacherEnabled = false;
    private boolean explicitChoicesEnabled = true;
    private SpellAbility teacherChoice = null;
    private final Map<SpellAbility, SpellAbility> originalActions = new IdentityHashMap<>();
    private final Set<SpellAbility> conditionalRoots = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<SpellAbility> explicitActions = Collections.newSetFromMap(new IdentityHashMap<>());
    private SpellAbility explicitAction;
    private List<AbilitySub> explicitModes;
    private int explicitActionCount;
    private int delegatedActionCount;
    private int modelSearchCount;
    private int delegatedSearchCount;
    private SpellAbility declinedSearch;
    // Selected delegation boundaries, not a total of all inherited AI decisions.
    private int delegatedMulliganKeepCount;
    private int delegatedMulliganTuckCount;
    private int delegatedAttackDeclarationCount;
    private int delegatedBlockDeclarationCount;
    private int delegatedCombatDamageAssignmentCount;
    private int delegatedManaPaymentCount;
    private int delegatedHandSizeDiscardCount;
    private int legacyDiscoveryPreparationCount;
    private int legacySelectedPreparationCount;

    public PlayerControllerRl(Game game, Player p, LobbyPlayer lp, IDecisionCallback callback) {
        super(game, p, lp);
        this.callback = callback;
    }

    @Override
    public void reveal(CardCollectionView cards, ZoneType zone, Player owner,
                       String messagePrefix, boolean addSuffix) {
        super.reveal(cards, zone, owner, messagePrefix, addSuffix);
        for (Card card : cards) {
            String name = card.getName();
            if (card.isFaceDown()) {
                final var view = card.getView();
                if (!view.canFaceDownBeShownTo(player.getView())) {
                    continue;
                }
                final var faceUp = view.getAlternateState();
                if (faceUp == null) {
                    continue;
                }
                name = faceUp.getOracleName();
            }
            if (name.isEmpty()) {
                continue;
            }
            callback.cardsRevealed(card.getOwner() == player, zone.name(),
                    List.of(card.getId()), List.of(name), List.of(card.isToken()));
        }
    }

    /** Publish only the contiguous visible prefix of each library. */
    public void publishVisibleLibraryTops() {
        for (Player owner : getGame().getPlayers()) {
            List<Integer> instanceIds = new ArrayList<>();
            List<String> cardNames = new ArrayList<>();
            List<Boolean> tokens = new ArrayList<>();
            for (Card card : owner.getCardsIn(ZoneType.Library)) {
                if (!card.mayPlayerLook(player)) {
                    break;
                }
                instanceIds.add(card.getId());
                cardNames.add(card.getName());
                tokens.add(card.isToken());
            }
            callback.libraryTopVisible(owner == player, instanceIds, cardNames, tokens);
        }
    }

    /**
     * Main RL decision hook: called every time this player has priority.
     *
     * Returns null  -> pass priority.
     * Returns list  -> play those SpellAbilities after mapping the selected RL
     *                  candidate back to an executable ability.
     */
    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        originalActions.clear();
        conditionalRoots.clear();
        // Forge AI's own pool: also graveyard, exile, command, and library tops.
        CardCollection pool = ComputerUtilAbility.getAvailableCards(getGame(), player);
        List<SpellAbility> rawPlayable = possibleActions(pool);
        rawCandidateCount += rawPlayable.size();
        List<SpellAbility> legalActions = new ArrayList<>();
        for (SpellAbility ability : rawPlayable) {
            if (ability.isManaAbility()) {
                manaCandidateFilteredCount++;
                continue;
            }
            if (!teacherEnabled && !ability.isLandAbility()) {
                try {
                    ActionDraft draft = new ActionDraft(ability, player);
                    if (!draft.getOptions().isEmpty()) {
                        SpellAbility root = draft.getRootAction();
                        legalActions.add(root);
                        originalActions.put(root, root);
                        conditionalRoots.add(root);
                    }
                    continue;
                } catch (UnsupportedOperationException unsupported) {
                    // Only unsupported slices keep the existing delegated preparation.
                }
            }
            legacyDiscoveryPreparationCount++;
            final SpellAbility prepared = prepareAction(ability);
            if (prepared != null) {
                legalActions.add(prepared);
                originalActions.put(prepared, ability);
            }
        }
        filteredCandidateCount += legalActions.size();
        if (legalActions.isEmpty()) {
            automaticPassCount++;
            return null;
        }

        if (teacherEnabled) {
            final List<SpellAbility> teacher = super.chooseSpellAbilityToPlay();
            teacherChoice = teacher == null || teacher.isEmpty() ? null : teacher.get(0);
        }
        List<SpellAbility> choice = callback.chooseActionsToPlay(legalActions);
        if (choice == null || choice.isEmpty()) {
            chosenPassActionCount++;
            return null;
        }
        return choice;
    }

    private List<SpellAbility> possibleActions(CardCollection pool) {
        Map<SpellAbility, Player> actors = new LinkedHashMap<>();
        for (Card card : pool) {
            for (var state : card.getStates()) {
                for (SpellAbility ability : card.getState(state).getSpellAbilities()) {
                    rememberActivators(ability, actors);
                }
            }
        }
        try {
            return ComputerUtilAbility.getSpellAbilities(pool, player).stream()
                    .map(ability -> ability.copy(player)).toList();
        } finally {
            // Native enumeration sets actors recursively; offered copies must not alter originals.
            actors.forEach(SpellAbility::setActivatingPlayer);
        }
    }

    private static void rememberActivators(SpellAbility ability, Map<SpellAbility, Player> actors) {
        if (actors.containsKey(ability)) {
            return;
        }
        actors.put(ability, ability.getActivatingPlayer());
        if (ability.getSubAbility() != null) {
            rememberActivators(ability.getSubAbility(), actors);
        }
        for (SpellAbility extra : ability.getAdditionalAbilities().values()) {
            rememberActivators(extra, actors);
        }
        for (List<AbilitySub> choices : ability.getAdditionalAbilityLists().values()) {
            for (AbilitySub choice : choices) {
                rememberActivators(choice, actors);
            }
        }
    }

    /** Complete delegated cost/target choices without adding policy decisions. */
    private SpellAbility prepareAction(SpellAbility original) {
        final List<SpellAbility> variants = ComputerUtilAbility.getOriginalAndAltCostAbilities(
                List.of(original), player);
        SpellAbility fallback = null;
        for (SpellAbility ability : variants) {
            final Card host = ability.getHostCard();
            final SpellAbility previousCast = host.getCastSA();
            if (ability.isSpell()) {
                // Forge evaluates cast-dependent targets against this candidate.
                host.setCastSA(ability);
            }
            try {
                if (!ability.canPlay()) {
                    continue;
                }
                if (ability.getApi() == ApiType.Charm && !hasEnoughLegalModes(ability)) {
                    invalidModeCandidateCount++;
                    continue;
                }

                final boolean isCharm = ability.getApi() == ApiType.Charm;
                final boolean hasX = ability.costHasX();
                if (hasX) {
                    // Complete X even when the AI would defer this legal action.
                    ComputerUtilCost.setMaxXValue(ability, player, false);
                }
                AiPlayDecision decision = AiPlayDecision.WillPlay;
                if (variants.size() > 1 || isCharm || hasX || usesTargeting(ability)) {
                    // Forge AI completes choices outside this high-level action boundary.
                    decision = getAi().canPlaySa(ability);
                    if (isCharm && decision != AiPlayDecision.WillPlay) {
                        invalidModeCandidateCount++;
                        continue;
                    }
                    if (usesTargeting(ability)
                            && !getGame().getStack().hasLegalTargeting(ability)) {
                        for (SpellAbility part = ability; part != null; part = part.getSubAbility()) {
                            if (part.usesTargeting() && !part.isTargetNumberValid()) {
                                part.resetTargets();
                                chooseTargetsFor(part);
                            }
                        }
                    }
                    if (usesTargeting(ability)
                            && !getGame().getStack().hasLegalTargeting(ability)) {
                        invalidTargetCandidateCount++;
                        continue;
                    }
                }
                if (ComputerUtilCost.canPayCost(ability, player, false)) {
                    if (decision == AiPlayDecision.WillPlay) {
                        return ability;
                    }
                    // The model may choose a legal action the AI would defer.
                    if (fallback == null) {
                        fallback = ability;
                    }
                }
            } finally {
                if (ability.isSpell()) {
                    host.setCastSA(previousCast);
                }
            }
        }
        return fallback;
    }

    /** Ask Forge AI for its own choice at each decision, as an imitation label. */
    public void setTeacherEnabled(boolean enabled) {
        teacherEnabled = enabled;
    }

    /** Registered control treatment: keep root discovery identical, delegate details. */
    public void setExplicitChoicesEnabled(boolean enabled) {
        explicitChoicesEnabled = enabled;
    }

    /** Forge AI's choice at the current decision; null means it would pass. */
    public SpellAbility getTeacherChoice() {
        return teacherChoice;
    }

    private boolean usesTargeting(SpellAbility sa) {
        SpellAbility current = sa;
        while (current != null) {
            if (current.usesTargeting()) {
                return true;
            }
            current = current.getSubAbility();
        }
        return false;
    }

    private boolean hasEnoughLegalModes(SpellAbility sa) {
        final List<AbilitySub> choices = CharmEffect.makePossibleOptions(sa);
        final int num = AbilityUtils.calculateAmount(
                sa.getHostCard(), sa.getParamOrDefault("CharmNum", "1"), sa);
        final int min = sa.hasParam("MinCharmNum")
                ? AbilityUtils.calculateAmount(
                        sa.getHostCard(), sa.getParam("MinCharmNum"), sa)
                : num;
        if (sa.hasParam("CanRepeatModes")) {
            return min == 0 || !choices.isEmpty();
        }
        return min <= choices.size();
    }

    @Override
    public boolean playChosenSpellAbility(SpellAbility sa) {
        chosenActionCount++;
        final boolean played = explicitActions.remove(sa)
                ? playExplicitSpellAbility(sa) : super.playChosenSpellAbility(sa);
        if (!played) {
            failedActionCount++;
        }
        return played;
    }

    /** Commit a fully selected proposal through Forge's ordinary announcement/payment path. */
    public boolean playChosenAction(ActionDraft draft) {
        return playChosenSpellAbility(acceptChosenAction(draft));
    }

    /** The offered root before delegated preparation, only in its current priority window. */
    public SpellAbility getOriginalAction(SpellAbility offered) {
        SpellAbility original = originalActions.get(offered);
        if (original == null) {
            throw new IllegalArgumentException("Action is not from this priority window");
        }
        return original;
    }

    /** Integer macro actions delegate details only after the root was selected. */
    public SpellAbility completeDelegatedAction(SpellAbility offered) {
        getOriginalAction(offered);
        delegatedActionCount++;
        if (!conditionalRoots.contains(offered)) {
            return offered;
        }
        legacySelectedPreparationCount++;
        SpellAbility prepared = prepareAction(offered.copy(player));
        if (prepared == null) {
            throw new IllegalStateException("Delegate cannot complete this root; use explicit choices");
        }
        return prepared;
    }

    /** Compile a proposal returned by the blocking callback; payment happens later. */
    public SpellAbility acceptChosenAction(ActionDraft draft) {
        SpellAbility selected = draft.finish();
        if (selected.getActivatingPlayer() != player || selected.getHostCard().getGame() != getGame()) {
            throw new IllegalArgumentException("Action proposal belongs to a different player or game");
        }
        explicitActions.add(selected);
        explicitActionCount++;
        return selected;
    }

    private boolean playExplicitSpellAbility(SpellAbility selected) {
        explicitAction = selected;
        explicitModes = null;
        if (selected.getApi() == ApiType.Charm) {
            explicitModes = new ArrayList<>();
            SpellAbility part = selected.getSubAbility();
            for (AbilitySub mode : selected.getChosenList()) {
                AbilitySub copy = (AbilitySub) part.copy(player);
                SpellAbility tail = copy;
                for (SpellAbility original = mode; original.getSubAbility() != null;
                     original = original.getSubAbility()) {
                    tail = tail.getSubAbility();
                    part = part.getSubAbility();
                }
                part = part.getSubAbility();
                tail.setSubAbility(null);
                explicitModes.add(copy);
            }
        }
        Map<MemorySet, CardCollection> reserved = new EnumMap<>(MemorySet.class);
        for (MemorySet set : List.of(MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL,
                MemorySet.HELD_MANA_SOURCES_FOR_MAIN2, MemorySet.HELD_MANA_SOURCES_FOR_DECLBLK,
                MemorySet.HELD_MANA_SOURCES_FOR_ENEMY_DECLBLK)) {
            final var memory = AiCardMemory.getMemorySet(player, set);
            reserved.put(set, new CardCollection(memory));
            // A delegate's strategic reservation cannot veto an explicit legal action.
            memory.clear();
        }
        try {
            return super.playChosenSpellAbility(selected);
        } finally {
            explicitAction = null;
            explicitModes = null;
            for (var saved : reserved.entrySet()) {
                final var memory = AiCardMemory.getMemorySet(player, saved.getKey());
                memory.clear();
                memory.addAll(saved.getValue());
            }
        }
    }

    @Override
    public List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> possible,
                                               int min, int num, boolean allowRepeat) {
        if (sa != explicitAction || explicitModes == null) {
            return super.chooseModeForAbility(sa, possible, min, num, allowRepeat);
        }
        if (explicitModes.size() < min || explicitModes.size() > num) {
            throw new IllegalStateException("Selected mode cardinality is no longer available");
        }
        Set<Map<String, String>> used = new java.util.HashSet<>();
        for (AbilitySub chosen : sa.getChosenList()) {
            if (possible.stream().noneMatch(mode -> mode.getMapParams().equals(chosen.getMapParams()))
                    || !allowRepeat && !used.add(chosen.getMapParams())) {
                throw new IllegalStateException("Selected mode is no longer available");
            }
        }
        // Native announcement rebuilds the chain; preserve the selected part's targets.
        return new ArrayList<>(explicitModes);
    }

    @Override
    public Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin,
            SpellAbility sa, CardCollection candidates, DelayedReveal delayedReveal,
            String prompt, boolean optional, Player decider) {
        // shortcut: one-card library searches only; widen when another callback profile is qualified.
        if (!explicitChoicesEnabled || teacherEnabled || decider != player || sa.getApi() != ApiType.ChangeZone
                || origin.size() != 1 || origin.get(0) != ZoneType.Library
                || !sa.getParamOrDefault("ChangeNum", "1").equals("1")
                || sa.hasParam("Defined") || sa.hasParam("AtRandom")
                || sa.hasParam("Optional")
                || candidates.stream().anyMatch(card -> card.getOwner() != player)
                || sa.getParamOrDefault("ChangeType", "Card").startsWith("EACH")) {
            delegatedSearchCount++;
            return super.chooseSingleCardForZoneChange(destination, origin, sa, candidates,
                    delayedReveal, prompt, optional, decider);
        }
        if (candidates.isEmpty()) { return null; }
        if (delayedReveal != null) { reveal(delayedReveal); }
        List<Card> options = new ArrayList<>(candidates);
        Map<Card, Long> timestamps = new IdentityHashMap<>();
        options.forEach(card -> timestamps.put(card, card.getGameTimestamp()));
        Card selected = callback.chooseCardForSearch(sa, options, optional);
        if (getGame().isGameOver()) { return null; }
        if (selected == null ? !optional : !timestamps.containsKey(selected)
                || selected.getGameTimestamp() != timestamps.get(selected)
                || !selected.isInZone(ZoneType.Library)) {
            throw new IllegalArgumentException("Resolution selection is not a current legal candidate");
        }
        modelSearchCount++;
        declinedSearch = selected == null ? sa : null;
        return selected;
    }

    @Override
    public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message,
            List<String> options, Card cardToShow, Map<String, Object> params) {
        if (sa == declinedSearch && mode == PlayerActionConfirmMode.ChangeZoneGeneral) {
            declinedSearch = null;
            return true;
        }
        return super.confirmAction(sa, mode, message, options, cardToShow, params);
    }

    public int getExplicitActionCount() { return explicitActionCount; }
    public int getDelegatedActionCount() { return delegatedActionCount; }
    public int getModelSearchCount() { return modelSearchCount; }
    public int getDelegatedSearchCount() { return delegatedSearchCount; }

    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        delegatedMulliganKeepCount++;
        return super.mulliganKeepHand(firstPlayer, cardsToReturn);
    }

    @Override
    public CardCollectionView tuckCardsViaMulligan(
            CardCollectionView hand, int cardsToReturn) {
        delegatedMulliganTuckCount++;
        CardCollectionView cards = super.tuckCardsViaMulligan(hand, cardsToReturn);
        cardsBottomed += cards.size();
        return cards;
    }

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        delegatedAttackDeclarationCount++;
        super.declareAttackers(attacker, combat);
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        delegatedBlockDeclarationCount++;
        super.declareBlockers(defender, combat);
    }

    @Override
    public Map<Card, Integer> assignCombatDamage(Card attacker, CardCollectionView blockers,
            CardCollectionView remaining, int damageDealt, GameEntity defender, boolean overrideOrder) {
        delegatedCombatDamageAssignmentCount++;
        return super.assignCombatDamage(attacker, blockers, remaining, damageDealt, defender, overrideOrder);
    }

    @Override
    public boolean payManaCost(ManaCost toPay, CostPartMana costPartMana, SpellAbility sa,
            String prompt, ManaConversionMatrix matrix, boolean effect) {
        delegatedManaPaymentCount++;
        return super.payManaCost(toPay, costPartMana, sa, prompt, matrix, effect);
    }

    @Override
    public CardCollectionView chooseCardsToDiscardToMaximumHandSize(int numDiscard) {
        delegatedHandSizeDiscardCount++;
        return super.chooseCardsToDiscardToMaximumHandSize(numDiscard);
    }

    public int getDelegatedMulliganKeepCount() { return delegatedMulliganKeepCount; }
    public int getDelegatedMulliganTuckCount() { return delegatedMulliganTuckCount; }
    public int getDelegatedAttackDeclarationCount() { return delegatedAttackDeclarationCount; }
    public int getDelegatedBlockDeclarationCount() { return delegatedBlockDeclarationCount; }
    public int getDelegatedCombatDamageAssignmentCount() { return delegatedCombatDamageAssignmentCount; }
    public int getDelegatedManaPaymentCount() { return delegatedManaPaymentCount; }
    public int getDelegatedHandSizeDiscardCount() { return delegatedHandSizeDiscardCount; }
    public int getLegacyDiscoveryPreparationCount() { return legacyDiscoveryPreparationCount; }
    public int getLegacySelectedPreparationCount() { return legacySelectedPreparationCount; }

    public int getRawCandidateCount() {
        return rawCandidateCount;
    }

    public int getFilteredCandidateCount() {
        return filteredCandidateCount;
    }

    public int getInvalidTargetCandidateCount() {
        return invalidTargetCandidateCount;
    }

    public int getInvalidModeCandidateCount() {
        return invalidModeCandidateCount;
    }

    public int getManaCandidateFilteredCount() {
        return manaCandidateFilteredCount;
    }

    public int getAutomaticPassCount() {
        return automaticPassCount;
    }

    public int getChosenActionCount() {
        return chosenActionCount;
    }

    public int getChosenPassActionCount() {
        return chosenPassActionCount;
    }

    public int getFailedActionCount() {
        return failedActionCount;
    }

    public int getMulligansTaken() {
        return player.getStats().getMulliganCount();
    }

    public int getCardsBottomed() {
        return cardsBottomed;
    }
}
