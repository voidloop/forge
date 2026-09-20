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
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
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
    private int failedActionCount = 0;

    public PlayerControllerRl(Game game, Player p, LobbyPlayer lp, IDecisionCallback callback) {
        super(game, p, lp);
        this.callback = callback;
    }

    @Override
    public void reveal(CardCollectionView cards, ZoneType zone, Player owner,
                       String messagePrefix, boolean addSuffix) {
        super.reveal(cards, zone, owner, messagePrefix, addSuffix);
        List<Integer> instanceIds = new ArrayList<>();
        List<String> cardNames = new ArrayList<>();
        List<Boolean> tokens = new ArrayList<>();
        for (Card card : cards) {
            instanceIds.add(card.getId());
            cardNames.add(card.getName());
            tokens.add(card.isToken());
        }
        callback.cardsRevealed(owner == player, zone.name(), instanceIds, cardNames, tokens);
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
        CardCollection pool = new CardCollection();
        pool.addAll(player.getCardsIn(ZoneType.Hand));
        pool.addAll(player.getCardsIn(ZoneType.Battlefield));
        List<SpellAbility> rawPlayable = ComputerUtilAbility.getSpellAbilities(pool, player);
        rawCandidateCount += rawPlayable.size();
        List<SpellAbility> legalActions = new ArrayList<>();
        for (SpellAbility ability : rawPlayable) {
            if (ability.isManaAbility()) {
                manaCandidateFilteredCount++;
                continue;
            }
            if (!ability.canPlay()) {
                continue;
            }
            if (ability.getApi() == ApiType.Charm && !hasEnoughLegalModes(ability)) {
                invalidModeCandidateCount++;
                continue;
            }

            final boolean isCharm = ability.getApi() == ApiType.Charm;
            if (isCharm || usesTargeting(ability)) {
                // Forge AI completes choices outside this high-level action boundary.
                final AiPlayDecision decision = getAi().canPlaySa(ability);
                if (isCharm && decision != AiPlayDecision.WillPlay) {
                    invalidModeCandidateCount++;
                    continue;
                }
                if (usesTargeting(ability)
                        && !getGame().getStack().hasLegalTargeting(ability)) {
                    ability.resetTargets();
                    chooseTargetsFor(ability);
                }
                if (usesTargeting(ability)
                        && !getGame().getStack().hasLegalTargeting(ability)) {
                    invalidTargetCandidateCount++;
                    continue;
                }
            }
            if (ComputerUtilCost.canPayCost(ability, player, false)) {
                legalActions.add(ability);
            }
        }
        filteredCandidateCount += legalActions.size();
        if (legalActions.isEmpty()) {
            automaticPassCount++;
            return null;
        }

        List<SpellAbility> choice = callback.chooseActionsToPlay(legalActions);
        if (choice == null || choice.isEmpty()) {
            chosenPassActionCount++;
            return null;
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
        final boolean played = super.playChosenSpellAbility(sa);
        if (!played) {
            failedActionCount++;
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

    public int getFailedActionCount() {
        return failedActionCount;
    }
}
