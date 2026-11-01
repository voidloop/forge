package forge.rl;

import com.google.common.eventbus.EventBus;
import forge.ai.LobbyPlayerAi;
import forge.game.event.*;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.List;

import static org.testng.Assert.*;

public class BaselineTraceTest {
    @BeforeClass public void initialize() { OriginalCompositeChoice.initialize(Path.of("..")); }

    @Test public void countsAllEventsAndReportsLimitsWithoutInventingAnOutcome() throws Exception {
        var game = FullGameTest.fixture(List.of(new LobbyPlayerAi("first", null), new LobbyPlayerAi("second", null)));
        var player = game.getRegisteredPlayers().get(0).getView();
        EventBus bus = new EventBus();
        BaselineTrace trace = new BaselineTrace(2, 10);
        bus.register(trace);
        String random = FullGameTest.randomState();
        bus.post(new GameEventTurnBegan(player, 1));
        var before = trace.snapshot();
        bus.post(new GameEvent() {
            public <T> T visit(IGameEventVisitor<T> visitor) { return null; }
        });
        assertEquals(trace.snapshot().get("events"), 2);
        bus.post(new GameEventTurnBegan(player, 3));
        assertEquals(before.get("turn"), 1);
        assertEquals(((List<?>) before.get("milestones")).size(), 1);
        assertEquals(FullGameTest.randomState(), random);
        assertEquals(trace.snapshot().get("limit"), "turns");
        assertFalse(trace.snapshot().containsKey("outcome"));
        BaselineTrace events = new BaselineTrace(10, 1);
        bus.register(events);
        bus.post(new GameEventGameFinished());
        bus.post(new GameEventGameFinished());
        assertEquals(events.snapshot().get("events"), 2);
        assertEquals(events.snapshot().get("limit"), "events");
        bus.post(new GameEventGameFinished());
        assertEquals(events.snapshot().get("events"), 2);
        assertThrows(IllegalArgumentException.class, () -> new BaselineTrace(0, 1));
    }

    @Test public void subscriberFailuresRemainVisibleDespiteEventBus() {
        BaselineTrace trace = new BaselineTrace(10, 10);
        EventBus bus = new EventBus();
        bus.register(trace);
        bus.post(new GameEventTurnBegan(null, 1));
        assertTrue(trace.snapshot().get("failure").toString().contains("NullPointerException"));
    }
}
