package forge.rl;

import forge.LobbyPlayer;
import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCost;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.CardCollection;
import forge.game.player.Player;
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
    private int chosenActionCount = 0;
    private int failedActionCount = 0;

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
                if (sa.canPlay() && ComputerUtilCost.canPayCost(sa, player, false)) {
                    allPlayable.add(sa);
                }
            }
            filteredCandidateCount += allPlayable.size();
        } catch (Exception e) {
            allPlayable = new ArrayList<>();
        }

        List<SpellAbility> choice = callback.chooseSpellAbilitiesToPlay(allPlayable);

        if (choice == null || choice.isEmpty()) {
            return null; // pass priority
        }
        return choice;
    }

    @Override
    public boolean playChosenSpellAbility(SpellAbility sa) {
        chosenActionCount++;
        boolean played = super.playChosenSpellAbility(sa);
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

    public int getChosenActionCount() {
        return chosenActionCount;
    }

    public int getFailedActionCount() {
        return failedActionCount;
    }
}
