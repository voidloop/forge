package forge.rl;

import forge.LobbyPlayer;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCost;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.ability.effects.CharmEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.List;

/**
 * PlayerController for the RL agent.
 *
 * All complex decisions (targeting, cost payment, blocking, etc.) fall through
 * to PlayerControllerAi so the agent only needs to learn the high-level action:
 * which spell/ability to play (or pass priority).
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
    private int chosenSpellActionCount = 0;
    private int chosenLandActionCount = 0;
    private int chosenActivatedAbilityActionCount = 0;
    private int targetExpandedWindowCount = 0;
    private int targetExpandedAttachAbilityCount = 0;
    private int targetExpandedAttachCandidateCount = 0;
    private int targetExpandedAttachCurrentTargetCandidateCount = 0;
    private int targetExpandedAttachCardTargetCount = 0;
    private int targetExpandedAttachPlayerTargetCount = 0;
    private int targetExpandedCandidatesUpTo8Count = 0;
    private int targetExpandedCandidates9To16Count = 0;
    private int targetExpandedCandidates17To32Count = 0;
    private int targetExpandedCandidates33To64Count = 0;
    private int targetExpandedCandidates65To128Count = 0;
    private int targetExpandedCandidatesOver128Count = 0;
    private int selectedAttachActionCount = 0;
    private int selectedAttachCurrentTargetActionCount = 0;
    private int selectedAttachCardTargetActionCount = 0;
    private int selectedAttachPlayerTargetActionCount = 0;
    private int selectedAttachOwnTargetActionCount = 0;
    private int selectedAttachOpponentTargetActionCount = 0;
    private String lastSelectedAttachSource = "";
    private String lastSelectedAttachTarget = "";
    private int failedActionCount = 0;
    private int failedUnplayableActionCount = 0;
    private int failedInvalidTargetActionCount = 0;
    private int failedUnpayableCostActionCount = 0;
    private int failedExecutionActionCount = 0;
    private String lastFailedActionReason = "";
    private String lastFailedAction = "";

    public PlayerControllerRl(Game game, Player p, LobbyPlayer lp, IDecisionCallback callback) {
        super(game, p, lp);
        this.callback = callback;
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
        // Build the list of currently playable SpellAbilities (same view the AI uses).
        List<SpellAbility> allPlayable;
        try {
            CardCollection pool = new CardCollection();
            pool.addAll(player.getCardsIn(ZoneType.Hand));
            pool.addAll(player.getCardsIn(ZoneType.Battlefield));
            List<SpellAbility> rawPlayable = ComputerUtilAbility.getSpellAbilities(pool, player);
            rawCandidateCount += rawPlayable.size();
            allPlayable = new ArrayList<>();
            for (SpellAbility sa : rawPlayable) {
                if (sa.isManaAbility()) {
                    manaCandidateFilteredCount++;
                    continue;
                }
                if (!sa.canPlay()) {
                    continue;
                }

                if (sa.getApi() == ApiType.Charm && !hasEnoughLegalModes(sa)) {
                    invalidModeCandidateCount++;
                    continue;
                }

                final boolean isCharm = sa.getApi() == ApiType.Charm;
                if (isCharm || usesTargeting(sa)) {
                    // The regular AI selection path prepares targets through
                    // canPlaySa before playChosenSpellAbility. Charm abilities
                    // also need this pass before moving to the stack: it chooses
                    // and stores their modes, which may introduce targets.
                    final AiPlayDecision decision = getAi().canPlaySa(sa);
                    if (isCharm && decision != AiPlayDecision.WillPlay) {
                        invalidModeCandidateCount++;
                        continue;
                    }
                    if (usesTargeting(sa)
                            && !getGame().getStack().hasLegalTargeting(sa)) {
                        sa.resetTargets();
                        chooseTargetsFor(sa);
                    }
                    if (usesTargeting(sa)
                            && !getGame().getStack().hasLegalTargeting(sa)) {
                        invalidTargetCandidateCount++;
                        continue;
                    }
                }

                if (ComputerUtilCost.canPayCost(sa, player, false)) {
                    allPlayable.add(sa);
                }
            }
            filteredCandidateCount += allPlayable.size();
        } catch (Exception e) {
            allPlayable = new ArrayList<>();
        }

        recordTargetExpandedDiagnostics(allPlayable);
        List<RlActionCandidate> actionCandidates = buildActionCandidates(allPlayable);

        if (actionCandidates.isEmpty()) {
            automaticPassCount++;
            return null;
        }

        List<RlActionCandidate> choice = callback.chooseActionCandidatesToPlay(actionCandidates);

        if (choice == null || choice.isEmpty()) {
            chosenPassActionCount++;
            return null; // pass priority
        }
        List<SpellAbility> abilities = new ArrayList<>();
        for (RlActionCandidate candidate : choice) {
            recordSelectedAttachAction(candidate);
            abilities.add(RlActionCandidateEnumerator.copyForExecution(candidate));
        }
        return abilities;
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

    private void recordTargetExpandedDiagnostics(List<SpellAbility> abilities) {
        int attachAbilities = 0;
        int attachCandidates = 0;
        for (SpellAbility ability : abilities) {
            if (!RlActionCandidateEnumerator.supportsSingleTargetAttach(ability)) {
                continue;
            }
            attachAbilities++;
            List<RlActionCandidate> candidates =
                    RlActionCandidateEnumerator.enumerateSingleTargetAttach(ability);
            attachCandidates += candidates.size();
            for (RlActionCandidate candidate : candidates) {
                if (candidate.getTargets().isEmpty()) {
                    continue;
                }
                GameObject target = candidate.getTargets().get(0);
                if (target instanceof Card) {
                    targetExpandedAttachCardTargetCount++;
                } else if (target instanceof Player) {
                    targetExpandedAttachPlayerTargetCount++;
                }
                if (target instanceof GameEntity
                        && ability.getHostCard().isAttachedToEntity(
                                (GameEntity) target)) {
                    targetExpandedAttachCurrentTargetCandidateCount++;
                }
            }
        }

        if (attachAbilities == 0) {
            return;
        }
        targetExpandedWindowCount++;
        targetExpandedAttachAbilityCount += attachAbilities;
        targetExpandedAttachCandidateCount += attachCandidates;
        int expandedCandidateCount =
                abilities.size() - attachAbilities + attachCandidates;
        if (expandedCandidateCount <= 8) {
            targetExpandedCandidatesUpTo8Count++;
        } else if (expandedCandidateCount <= 16) {
            targetExpandedCandidates9To16Count++;
        } else if (expandedCandidateCount <= 32) {
            targetExpandedCandidates17To32Count++;
        } else if (expandedCandidateCount <= 64) {
            targetExpandedCandidates33To64Count++;
        } else if (expandedCandidateCount <= 128) {
            targetExpandedCandidates65To128Count++;
        } else {
            targetExpandedCandidatesOver128Count++;
        }
    }

    private List<RlActionCandidate> buildActionCandidates(List<SpellAbility> abilities) {
        List<RlActionCandidate> candidates = new ArrayList<>();
        for (SpellAbility ability : abilities) {
            if (!RlActionCandidateEnumerator.supportsSingleTargetAttach(ability)) {
                candidates.add(RlActionCandidateEnumerator.targetless(ability));
                continue;
            }

            List<RlActionCandidate> expanded =
                    RlActionCandidateEnumerator.enumerateSingleTargetAttach(ability);
            if (expanded.isEmpty()) {
                candidates.add(RlActionCandidateEnumerator.targetless(ability));
            } else {
                candidates.addAll(expanded);
            }
        }
        return candidates;
    }

    private void recordSelectedAttachAction(RlActionCandidate candidate) {
        if (!RlActionCandidateEnumerator.supportsSingleTargetAttach(candidate.getAbility())) {
            return;
        }
        selectedAttachActionCount++;
        lastSelectedAttachSource = candidate.getAbility().getHostCard().getName();
        if (candidate.getTargets().isEmpty()) {
            lastSelectedAttachTarget = "";
            return;
        }

        GameObject target = candidate.getTargets().get(0);
        lastSelectedAttachTarget = target.toString();
        if (target instanceof Card) {
            selectedAttachCardTargetActionCount++;
            Player controller = ((Card) target).getController();
            if (player.equals(controller)) {
                selectedAttachOwnTargetActionCount++;
            } else {
                selectedAttachOpponentTargetActionCount++;
            }
        } else if (target instanceof Player) {
            selectedAttachPlayerTargetActionCount++;
            if (player.equals(target)) {
                selectedAttachOwnTargetActionCount++;
            } else {
                selectedAttachOpponentTargetActionCount++;
            }
        }
        if (target instanceof GameEntity
                && candidate.getAbility().getHostCard().isAttachedToEntity(
                        (GameEntity) target)) {
            selectedAttachCurrentTargetActionCount++;
        }
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
        if (sa.isSpell()) {
            chosenSpellActionCount++;
        } else if (sa.isLandAbility()) {
            chosenLandActionCount++;
        } else if (sa.isActivatedAbility()) {
            chosenActivatedAbilityActionCount++;
        }
        final boolean playable = sa.canPlay();
        final boolean validTargets = !usesTargeting(sa)
                || getGame().getStack().hasLegalTargeting(sa);
        final boolean payableCost = ComputerUtilCost.canPayCost(sa, player, false);
        boolean played = super.playChosenSpellAbility(sa);
        if (!played) {
            failedActionCount++;
            if (!playable) {
                failedUnplayableActionCount++;
                lastFailedActionReason = "unplayable";
            } else if (!validTargets) {
                failedInvalidTargetActionCount++;
                lastFailedActionReason = "invalid_target";
            } else if (!payableCost) {
                failedUnpayableCostActionCount++;
                lastFailedActionReason = "unpayable_cost";
            } else {
                failedExecutionActionCount++;
                lastFailedActionReason = "execution";
            }
            lastFailedAction = sa.getHostCard().getName() + ": " + sa;
        }
        return played;
    }

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

    public int getChosenSpellActionCount() {
        return chosenSpellActionCount;
    }

    public int getChosenLandActionCount() {
        return chosenLandActionCount;
    }

    public int getChosenActivatedAbilityActionCount() {
        return chosenActivatedAbilityActionCount;
    }

    public int getTargetExpandedWindowCount() {
        return targetExpandedWindowCount;
    }

    public int getTargetExpandedAttachAbilityCount() {
        return targetExpandedAttachAbilityCount;
    }

    public int getTargetExpandedAttachCandidateCount() {
        return targetExpandedAttachCandidateCount;
    }

    public int getTargetExpandedAttachCurrentTargetCandidateCount() {
        return targetExpandedAttachCurrentTargetCandidateCount;
    }

    public int getTargetExpandedAttachCardTargetCount() {
        return targetExpandedAttachCardTargetCount;
    }

    public int getTargetExpandedAttachPlayerTargetCount() {
        return targetExpandedAttachPlayerTargetCount;
    }

    public int getTargetExpandedCandidatesUpTo8Count() {
        return targetExpandedCandidatesUpTo8Count;
    }

    public int getTargetExpandedCandidates9To16Count() {
        return targetExpandedCandidates9To16Count;
    }

    public int getTargetExpandedCandidates17To32Count() {
        return targetExpandedCandidates17To32Count;
    }

    public int getTargetExpandedCandidates33To64Count() {
        return targetExpandedCandidates33To64Count;
    }

    public int getTargetExpandedCandidates65To128Count() {
        return targetExpandedCandidates65To128Count;
    }

    public int getTargetExpandedCandidatesOver128Count() {
        return targetExpandedCandidatesOver128Count;
    }

    public int getSelectedAttachActionCount() {
        return selectedAttachActionCount;
    }

    public int getSelectedAttachCurrentTargetActionCount() {
        return selectedAttachCurrentTargetActionCount;
    }

    public int getSelectedAttachCardTargetActionCount() {
        return selectedAttachCardTargetActionCount;
    }

    public int getSelectedAttachPlayerTargetActionCount() {
        return selectedAttachPlayerTargetActionCount;
    }

    public int getSelectedAttachOwnTargetActionCount() {
        return selectedAttachOwnTargetActionCount;
    }

    public int getSelectedAttachOpponentTargetActionCount() {
        return selectedAttachOpponentTargetActionCount;
    }

    public String getLastSelectedAttachSource() {
        return lastSelectedAttachSource;
    }

    public String getLastSelectedAttachTarget() {
        return lastSelectedAttachTarget;
    }

    public int getFailedActionCount() {
        return failedActionCount;
    }

    public int getFailedUnplayableActionCount() {
        return failedUnplayableActionCount;
    }

    public int getFailedInvalidTargetActionCount() {
        return failedInvalidTargetActionCount;
    }

    public int getFailedUnpayableCostActionCount() {
        return failedUnpayableCostActionCount;
    }

    public int getFailedExecutionActionCount() {
        return failedExecutionActionCount;
    }

    public String getLastFailedActionReason() {
        return lastFailedActionReason;
    }

    public String getLastFailedAction() {
        return lastFailedAction;
    }
}
