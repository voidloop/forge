package forge.rl;

import forge.game.GameEntity;
import forge.game.spellability.SpellAbility;

import java.util.List;

/**
 * Thin Java interface implemented from Python via JPype.
 * Called each time the RL player has priority and must decide what to play.
 *
 * Return null → pass priority (end turn / pass stack)
 * Return a non-empty list → play those action candidates in order
 */
public interface IDecisionCallback {

    /**
     * @param legalActions  list of action candidates the player may legally play right now
     *                      (may be empty if only "pass" is available)
     * @return chosen action candidates to play, or null to pass priority
     */
    List<SpellAbility> chooseActionsToPlay(List<SpellAbility> legalActions);

    /** Facts explicitly shown to the RL player outside a priority decision. */
    default void cardsRevealed(boolean ownerIsObserver, String zone,
                               List<Integer> instanceIds, List<String> cardNames,
                               List<Boolean> tokens) { }

    /** Current visible prefix of a library; an empty list clears old position data. */
    default void libraryTopVisible(boolean ownerIsObserver, List<Integer> instanceIds,
                                   List<String> cardNames, List<Boolean> tokens) { }

    /**
     * Spike only: called for a single-target ability the RL agent just chose to
     * play, so it can pick the target itself instead of Forge AI.
     *
     * @param ability    the ability being targeted (already chosen to play)
     * @param candidates every legal target for {@code ability} right now
     * @return the chosen target, or null to fall back to Forge AI targeting
     */
    default GameEntity chooseTarget(SpellAbility ability, List<GameEntity> candidates) {
        return null;
    }
}
