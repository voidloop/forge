package forge.rl;

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
    List<RlActionCandidate> chooseActionCandidatesToPlay(List<RlActionCandidate> legalActions);

    /** Facts explicitly shown to the RL player outside a priority decision. */
    default void cardsRevealed(boolean ownerIsObserver, String zone,
                               List<Integer> instanceIds, List<String> cardNames,
                               List<Boolean> tokens) { }

    /** Current visible prefix of a library; an empty list clears old position data. */
    default void libraryTopVisible(boolean ownerIsObserver, List<Integer> instanceIds,
                                   List<String> cardNames, List<Boolean> tokens) { }
}
