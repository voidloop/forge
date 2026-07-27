package forge.rl;

import forge.LobbyPlayer;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCost;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.ability.effects.CharmEffect;
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
     * Returns list  -> play those SpellAbilities (the first one is executed; the AI's
     *                  playChosenSpellAbility handles targeting / cost payment).
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

        if (allPlayable.isEmpty()) {
            automaticPassCount++;
            return null;
        }

        List<SpellAbility> choice = callback.chooseSpellAbilitiesToPlay(allPlayable);

        if (choice == null || choice.isEmpty()) {
            chosenPassActionCount++;
            return null; // pass priority
        }
        return choice;
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
