package forge.game.trigger;

import forge.game.card.Card;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.HashMap;
import java.util.Map;

public class TriggerTest {

    private static Trigger changesZoneTrigger(final String origin, final String destination) {
        final Map<String, String> params = new HashMap<>();
        if (origin != null) {
            params.put("Origin", origin);
        }
        if (destination != null) {
            params.put("Destination", destination);
        }
        return TriggerType.ChangesZone.createTrigger(params, new Card(1, null), false);
    }

    @Test
    public void looksBackInTimeHandlesMissingZones() {
        Assert.assertFalse(changesZoneTrigger(null, null).looksBackInTime());
        Assert.assertFalse(changesZoneTrigger(null, "Battlefield").looksBackInTime());
    }

    @Test
    public void looksBackInTimeRecognizesRelevantZoneChanges() {
        Assert.assertTrue(changesZoneTrigger("Battlefield", null).looksBackInTime());
        Assert.assertTrue(changesZoneTrigger("Graveyard", "Exile").looksBackInTime());
        Assert.assertTrue(changesZoneTrigger(null, "Library").looksBackInTime());
        Assert.assertTrue(changesZoneTrigger(null, "Hand").looksBackInTime());
    }
}
