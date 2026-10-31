package forge.rl;

import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.CardCollection;
import forge.util.MyRandom;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.testng.Assert.*;

public class SelectionTeacherTest {
    private record Entry(Map<String, Object> request, List<Integer> answer) { }
    private static final class Recording implements ISelectionRecordCallback {
        final List<Entry> entries = new ArrayList<>();
        Map<String, Object> pending;
        @Override public void before(Map<String, Object> request) {
            assertNull(pending, "Nested reasoning was exported");
            pending = request;
        }
        @Override public void after(List<Integer> answer) {
            assertNotNull(pending);
            entries.add(new Entry(pending, List.copyOf(answer)));
            pending = null;
        }
    }
    private record Outcome(List<List<Object>> states, long randomAfter) { }

    @BeforeClass public void initialize() { OriginalCompositeChoice.initialize(Path.of("..")); }

    private static List<Object> state(NativeSelectionOptionsTest.Fixture fixture) {
        List<Object> result = new ArrayList<>(OriginalCompositeChoice.observe(fixture.game()));
        result.add(fixture.game().getRegisteredPlayers().indexOf(fixture.host().getChosenPlayer()));
        result.add(fixture.host().getChosenCards().stream().map(card -> card.getName()).toList());
        return List.copyOf(result);
    }

    private static Outcome execute(NativeSelectionOptionsTest.Fixture fixture) {
        List<List<Object>> states = new ArrayList<>();
        states.add(state(fixture));
        for (String script : List.of(
                "DB$ ChoosePlayer | Choices$ Player.Opponent | DontNotify$ True",
                "DB$ ChooseCard | Choices$ Creature.YouCtrl | Amount$ 2 | Mandatory$ True",
                "DB$ Tap | CardChoices$ Creature.YouCtrl+untapped | ChoiceAmount$ 1",
                "DB$ Tap | CardChoices$ Creature.YouCtrl+untapped | ChoiceAmount$ 1",
                "DB$ ChooseCard | Choices$ Creature.YouCtrl | Amount$ 2 | MinAmount$ 0 | AIMaxAmount$ 0")) {
            NativeSelectionOptionsTest.resolve(fixture.host(), fixture.actor(), script);
            states.add(state(fixture));
        }
        return new Outcome(states, MyRandom.getRandom().nextLong());
    }

    @Test public void recordsFinalAnswersNeutrallyAndReplaysOnNewObjects() {
        MyRandom.setRandom(new Random(0));
        Outcome baseline = execute(NativeSelectionOptionsTest.fixture(new LobbyPlayerAi("first", null)));
        Recording recording = new Recording();
        MyRandom.setRandom(new Random(0));
        var teacher = NativeSelectionOptionsTest.fixture(new LobbyPlayerSelectionTeacher("first", recording));
        Outcome recorded = execute(teacher);
        assertEquals(recorded, baseline, "Recorder changed native facts or RNG");
        assertNull(recording.pending);
        assertEquals(recording.entries.stream().map(entry -> ((Map<?, ?>) entry.request().get("decision")).get("kind")).toList(),
                List.of("single_entity", "cards", "entities", "entities", "cards"));
        assertEquals(recording.entries.get(1).answer().size(), 2, "Multiple selection was not recorded in full");
        assertEquals(recording.entries.get(4).answer(), List.of(), "Empty optional answer was changed");
        int[] cursor = {0};
        MyRandom.setRandom(new Random(0));
        var replay = NativeSelectionOptionsTest.fixture(new LobbyPlayerRl("first", request -> {
            assertTrue(cursor[0] < recording.entries.size(), "Recording exhausted");
            Entry entry = recording.entries.get(cursor[0]++);
            assertEquals(request, entry.request(), "Question diverged");
            return entry.answer();
        }));
        assertNotSame(replay.first(), teacher.first());
        assertNotSame(replay.host(), teacher.host());
        Outcome applied = execute(replay);
        assertEquals(applied.states(), recorded.states());
        assertEquals(cursor[0], recording.entries.size());
    }

    @Test public void nativeFailuresLeavePendingQuestionsAndStopTeacher() {
        Recording recording = new Recording();
        var fixture = NativeSelectionOptionsTest.fixture(new LobbyPlayerSelectionTeacher("first", recording));
        var controller = fixture.actor().getController();
        // Native AiController rejects a missing ability; it must never become an empty answer.
        assertThrows(UnsupportedOperationException.class, () -> controller.chooseCardsForEffect(
                new CardCollection(fixture.first()), null, "invalid native context", 1, 1, false, null));
        assertNotNull(recording.pending);
        assertTrue(recording.entries.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> controller.chooseCardsForEffect(
                new CardCollection(fixture.first()), null, "stopped", 1, 1, false, null));
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toRealPath();
        for (var entry : Map.of(Game.class, "forge-game", PlayerControllerAi.class, "forge-ai", MyRandom.class, "forge-core").entrySet()) {
            assertEquals(Path.of(entry.getKey().getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath(),
                    root.resolve(entry.getValue() + "/target/classes").toRealPath());
        }
        OriginalCompositeChoice.initialize(root);
        var test = new SelectionTeacherTest();
        test.recordsFinalAnswersNeutrallyAndReplaysOnNewObjects();
        test.nativeFailuresLeavePendingQuestionsAndStopTeacher();
        System.out.println("PASS: selection teacher is neutral to native facts/RNG; final answers replay on new objects without AI; errors stay incomplete");
    }
}
