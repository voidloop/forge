package forge.rl;

import java.util.List;

/** Observer-filtered card-knowledge events delivered to the Python bridge. */
public interface IKnowledgeEventCallback {
    void cardZoneChanged(int instanceId, String cardName, boolean ownerIsObserver,
                         boolean isToken, String fromZone, String toZone);

    void playerShuffled(boolean ownerIsObserver);

    void cardsRevealed(boolean ownerIsObserver, String zone, List<Integer> instanceIds,
                       List<String> cardNames, List<Boolean> tokens);

    void libraryTopVisible(boolean ownerIsObserver, List<Integer> instanceIds,
                           List<String> cardNames, List<Boolean> tokens);
}
