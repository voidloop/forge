package forge.rl;

import forge.game.spellability.SpellAbility;
import java.util.List;

/**
 * Thin Java interface implemented from Python via JPype.
 * Called each time the RL player has priority and must decide what to play.
 *
 * Return null → pass priority (end turn / pass stack)
 * Return a non-empty list → play those SpellAbilities in order
 */
public interface IDecisionCallback {

    /**
     * @param legalActions  list of SpellAbilities the player may legally play right now
     *                      (may be empty if only "pass" is available)
     * @return chosen SpellAbilities to play, or null to pass priority
     */
    List<SpellAbility> chooseSpellAbilitiesToPlay(List<SpellAbility> legalActions);
}

