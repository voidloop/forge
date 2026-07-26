package forge.rl;

import forge.LobbyPlayer;
import forge.ai.AiController;
import forge.ai.ComputerUtilAbility;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

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

    public PlayerControllerRl(Game game, Player p, LobbyPlayer lp, IDecisionCallback callback) {
        super(game, p, lp);
        this.callback = callback;
    }

    /**
     * Main RL decision hook: called every time this player has priority.
     *
     * Returns null  → pass priority.
     * Returns list  → play those SpellAbilities (the first one is executed; the AI's
     *                 playChosenSpellAbility handles targeting / cost payment).
     */
    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        // Build the list of currently playable SpellAbilities (same view the AI uses).
        List<SpellAbility> allPlayable;
        try {
            allPlayable = ComputerUtilAbility.getSpellAbilities(
                    player.getCardsIn(forge.game.zone.ZoneType.Hand)
                            .appendedList(player.getCardsIn(forge.game.zone.ZoneType.Battlefield)),
                    player);
        } catch (Exception e) {
            allPlayable = new ArrayList<>();
        }

        List<SpellAbility> choice = callback.chooseSpellAbilitiesToPlay(allPlayable);

        if (choice == null || choice.isEmpty()) {
            return null; // pass priority
        }
        return choice;
    }
}

