package forge.rl;

import forge.rl.decisions.PriorityRequest;
import forge.rl.decisions.SelectionRequest;
import forge.rl.decisions.SelectionExecutor;

import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.CardCollection;
import forge.game.phase.PhaseType;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.testng.Assert.*;

public class PlayerControllerTeacherTest {
    private record Entry(Map<String, Object> request, List<Integer> answer) { }
    private static final class Recording implements IDecisionRecordCallback {
        final List<Entry> entries = new ArrayList<>();
        Map<String, Object> pending;
        @Override public void before(Map<String, Object> data) {
            assertNull(pending, "Nested reasoning was exported");
            pending = data;
        }
        @Override public void after(List<Integer> result) {
            assertNotNull(pending);
            entries.add(new Entry(pending, List.copyOf(result)));
            pending = null;
        }
    }
    private record Outcome(List<List<Object>> states, long randomAfter) { }

    @BeforeClass public void initialize() { OriginalCompositeChoice.initialize(Path.of("..")); }

    private static List<Object> state(NativeDecisionOptionsTest.Fixture fixture) {
        List<Object> result = new ArrayList<>(OriginalCompositeChoice.observe(fixture.game()));
        result.add(fixture.game().getRegisteredPlayers().indexOf(fixture.host().getChosenPlayer()));
        result.add(fixture.host().getChosenCards().stream().map(card -> card.getName()).toList());
        return List.copyOf(result);
    }

    private static Outcome execute(NativeDecisionOptionsTest.Fixture fixture) {
        List<List<Object>> states = new ArrayList<>();
        states.add(state(fixture));
        for (String script : List.of(
                "DB$ ChoosePlayer | Choices$ Player.Opponent | DontNotify$ True",
                "DB$ ChooseCard | Choices$ Creature.YouCtrl | Amount$ 2 | Mandatory$ True",
                "DB$ Tap | CardChoices$ Creature.YouCtrl+untapped | ChoiceAmount$ 1",
                "DB$ Tap | CardChoices$ Creature.YouCtrl+untapped | ChoiceAmount$ 1",
                "DB$ ChooseCard | Choices$ Creature.YouCtrl | Amount$ 2 | MinAmount$ 0 | AIMaxAmount$ 0")) {
            NativeDecisionOptionsTest.resolve(fixture.host(), fixture.actor(), script);
            states.add(state(fixture));
        }
        return new Outcome(states, MyRandom.getRandom().nextLong());
    }

    @Test public void recordsFinalAnswersNeutrallyAndReplaysOnNewObjects() {
        MyRandom.setRandom(new Random(0));
        Outcome baseline = execute(NativeDecisionOptionsTest.fixture(new LobbyPlayerAi("first", null)));
        Recording recording = new Recording();
        MyRandom.setRandom(new Random(0));
        var teacher = NativeDecisionOptionsTest.fixture(new LobbyPlayerTeacher("first", recording));
        Outcome recorded = execute(teacher);
        assertEquals(recorded, baseline, "Recorder changed native facts or RNG");
        assertNull(recording.pending);
        assertEquals(recording.entries.stream().map(entry -> ((Map<?, ?>) entry.request().get("question")).get("kind")).toList(),
                List.of("single_entity", "cards", "entities", "entities", "cards"));
        assertEquals(recording.entries.get(1).answer().size(), 2, "Multiple selection was not recorded in full");
        assertEquals(recording.entries.get(4).answer(), List.of(), "Empty optional answer was changed");
        int[] cursor = {0};
        MyRandom.setRandom(new Random(0));
        var replay = NativeDecisionOptionsTest.fixture(new LobbyPlayerRl("first", req -> {
            assertTrue(cursor[0] < recording.entries.size(), "Recording exhausted");
            Entry entry = recording.entries.get(cursor[0]++);
            assertEquals(req, entry.request(), "Request diverged");
            return entry.answer();
        }));
        assertNotSame(replay.first(), teacher.first());
        assertNotSame(replay.host(), teacher.host());
        Outcome applied = execute(replay);
        assertEquals(applied.states(), recorded.states());
        assertEquals(cursor[0], recording.entries.size());
    }
}
