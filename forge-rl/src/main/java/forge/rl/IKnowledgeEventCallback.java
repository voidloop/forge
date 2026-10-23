package forge.rl;

import java.util.List;
import java.util.Map;

/** Observer-filtered card-knowledge events delivered to the Python bridge. */
public interface IKnowledgeEventCallback {
    // A null name carries a public physical reference, never a hidden identity.
    void cardZoneChanged(int instanceId, String cardName, boolean ownerIsObserver,
                         boolean isToken, String fromZone, String toZone);

    void anonymousZoneChange(boolean ownerIsObserver, String fromZone, String toZone);

    void playerShuffled(boolean ownerIsObserver);

    void cardsRevealed(boolean ownerIsObserver, String zone, List<Integer> instanceIds,
                       List<String> cardNames, List<Boolean> tokens);

    void libraryTopVisible(boolean ownerIsObserver, List<Integer> instanceIds,
                           List<String> cardNames, List<Boolean> tokens);

    void publicEvent(String kind, boolean playerIsObserver, Integer instanceId,
                     String fromZone, String toZone, Integer amount);

    void publicEffectCreated(Map<String, Object> effect);

    void publicEffectRemoved(int instanceId);
}
