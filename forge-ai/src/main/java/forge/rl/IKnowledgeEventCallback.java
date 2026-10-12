package forge.rl;

import java.util.List;
import forge.game.card.Card;

/** Observer-filtered card-knowledge events delivered to the Python bridge. */
public interface IKnowledgeEventCallback {
    void ownLibraryKnown(List<String> cardNames);

    void cardZoneChanged(int instanceId, String cardName, boolean ownerIsObserver,
                         boolean isToken, String fromZone, String toZone,
                         Integer drawPosition);

    void anonymousZoneChange(boolean ownerIsObserver, String fromZone, String toZone,
                             Integer visibleInstanceId, Integer drawPosition);

    void playerShuffled(boolean ownerIsObserver);

    void publicEvent(String kind, boolean playerIsObserver, Integer instanceId,
                     String fromZone, String toZone, Integer amount);

    void publicEffectCreated(Card effect);

    void publicEffectRemoved(int instanceId);
}
