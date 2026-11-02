package forge.rl;

import forge.rl.decisions.PriorityRecorder;

import forge.rl.decisions.PriorityRequest;

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
        assertEquals(recording.entries.stream().map(entry -> ((Map<?, ?>) entry.request().get("options")).get("kind")).toList(),
                List.of("single_entity", "cards", "entities", "entities", "cards"));
        assertEquals(recording.entries.get(1).answer().size(), 2, "Multiple selection was not recorded in full");
        assertEquals(recording.entries.get(4).answer(), List.of(), "Empty optional answer was changed");
        int[] cursor = {0};
        MyRandom.setRandom(new Random(0));
        var replay = NativeDecisionOptionsTest.fixture(new LobbyPlayerRl("first", request -> {
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
        var fixture = NativeDecisionOptionsTest.fixture(new LobbyPlayerTeacher("first", recording));
        var controller = fixture.actor().getController();
        // Native AiController rejects a missing ability; it must never become an empty answer.
        assertThrows(UnsupportedOperationException.class, () -> controller.chooseCardsForEffect(
                new CardCollection(fixture.first()), null, "invalid native context", 1, 1, false, null));
        assertNotNull(recording.pending);
        assertTrue(recording.entries.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> controller.chooseCardsForEffect(
                new CardCollection(fixture.first()), null, "stopped", 1, 1, false, null));
    }

    private static Outcome executePriority(BasicLandPriorityTest.Fixture fixture) {
        List<List<Object>> states = new ArrayList<>();
        var controller = fixture.player().getController();
        states.add(OriginalCompositeChoice.observe(fixture.game()));
        var chosen = controller.chooseSpellAbilityToPlay();
        states.add(OriginalCompositeChoice.observe(fixture.game()));
        assertEquals(states.get(1), states.get(0), "Decision executed a land");
        if (chosen != null) {
            assertEquals(chosen.size(), 1);
            var card = chosen.get(0).getHostCard();
            assertTrue(controller.playChosenSpellAbility(chosen.get(0)));
            assertTrue(fixture.game().getCardState(card, null).isInZone(ZoneType.Battlefield));
            assertEquals(fixture.player().getLandsPlayedThisTurn(), 1);
            states.add(OriginalCompositeChoice.observe(fixture.game()));
            assertNull(controller.chooseSpellAbilityToPlay());
            states.add(OriginalCompositeChoice.observe(fixture.game()));
        }
        return new Outcome(states, MyRandom.getRandom().nextLong());
    }

    private static BasicLandPriorityTest.Fixture priorityFixture(forge.LobbyPlayer lobby, int context) {
        var fixture = BasicLandPriorityTest.fixture(lobby);
        fixture.game().getPhaseHandler().devModeSet(context == 1 ? PhaseType.COMBAT_BEGIN
                : context == 3 ? PhaseType.MAIN2 : PhaseType.MAIN1,
                context == 2 ? fixture.opponent() : fixture.player(), false, context == 3 ? 6 : 1);
        fixture.game().getPhaseHandler().setPriority(fixture.player());
        return fixture;
    }

    @Test public void priorityIsNeutralAndReplaysBeforeAndAfterLandExecution() {
        for (int context = 0; context < 4; context++) {
            MyRandom.setRandom(new Random(0));
            Outcome baseline = executePriority(priorityFixture(new LobbyPlayerAi("first", null), context));
            Recording recording = new Recording();
            MyRandom.setRandom(new Random(0));
            var teacher = priorityFixture(new LobbyPlayerTeacher("first", recording), context);
            Outcome recorded = executePriority(teacher);
            assertEquals(recorded, baseline, "Priority recording changed native facts/RNG");
            assertNull(recording.pending);
            assertEquals(recording.entries.get(recording.entries.size() - 1).answer(), List.of(0));
            if (context == 0 || context == 3) {
                assertEquals(recording.entries.size(), 2);
                assertTrue(recording.entries.get(0).answer().get(0) > 0);
            } else { assertEquals(recording.entries.size(), 1); }
            int[] cursor = {0};
            MyRandom.setRandom(new Random(0));
            var replay = priorityFixture(new LobbyPlayerRl("first", request -> {
                Entry entry = recording.entries.get(cursor[0]++);
                assertEquals(request, entry.request(), "Priority request diverged");
                return entry.answer();
            }), context);
            assertNotSame(replay.forest(), teacher.forest());
            assertEquals(executePriority(replay).states(), recorded.states());
            assertEquals(cursor[0], recording.entries.size());
            ((PlayerControllerTeacher) teacher.player().getController()).closeObservation();
            ((PlayerControllerRl) replay.player().getController()).closeObservation();
        }
    }

    @Test public void priorityErrorsRemainDistinctFromAnswersAndExecution() {
        Recording staleRecording = new Recording();
        var stale = priorityFixture(new LobbyPlayerTeacher("first", staleRecording), 0);
        var controller = stale.player().getController();
        var choice = controller.chooseSpellAbilityToPlay();
        assertEquals(staleRecording.entries.size(), 1, "Answer must be saved before execution");
        stale.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_BEGIN, stale.player(), false, 1);
        assertThrows(IllegalArgumentException.class, () -> controller.playChosenSpellAbility(choice.get(0)));
        assertEquals(stale.player().getLandsPlayedThisTurn(), 0);
        assertThrows(IllegalArgumentException.class, controller::chooseSpellAbilityToPlay);

        Recording repeatedRecording = new Recording();
        var repeated = priorityFixture(new LobbyPlayerTeacher("first", repeatedRecording), 0);
        var repeatedController = repeated.player().getController();
        var selected = repeatedController.chooseSpellAbilityToPlay().get(0);
        assertTrue(repeatedController.playChosenSpellAbility(selected));
        assertThrows(IllegalArgumentException.class, () -> repeatedController.playChosenSpellAbility(selected));
        assertEquals(repeated.player().getLandsPlayedThisTurn(), 1);
        assertEquals(repeatedRecording.entries.size(), 1);

        Recording unboundRecording = new Recording();
        var unbound = priorityFixture(new LobbyPlayerTeacher("first", unboundRecording), 0);
        assertThrows(IllegalArgumentException.class, () -> unbound.player().getController()
                .playChosenSpellAbility(unbound.forest().getFirstSpellAbility()));
        assertTrue(unboundRecording.entries.isEmpty());
        assertEquals(unbound.player().getLandsPlayedThisTurn(), 0);

        Recording unsupported = new Recording();
        var bad = priorityFixture(new LobbyPlayerTeacher("first", unsupported), 0);
        OriginalCompositeChoice.add(bad.game(), bad.player(), "Llanowar Elves");
        assertThrows(UnsupportedOperationException.class, () -> bad.player().getController().chooseSpellAbilityToPlay());
        assertTrue(unsupported.entries.isEmpty());
        assertNull(unsupported.pending);

        Recording pending = new Recording();
        var broken = priorityFixture(new LobbyPlayerTeacher("first", new IDecisionRecordCallback() {
            @Override public void before(Map<String, Object> request) { pending.before(request); }
            @Override public void after(List<Integer> answer) { throw new IllegalStateException("Recorder failed"); }
        }), 0);
        assertThrows(IllegalStateException.class, () -> broken.player().getController().chooseSpellAbilityToPlay());
        assertNotNull(pending.pending);
        assertTrue(pending.entries.isEmpty());
        assertEquals(broken.player().getLandsPlayedThisTurn(), 0);
        assertThrows(IllegalStateException.class, () -> broken.player().getController().chooseSpellAbilityToPlay());

        var fixture = priorityFixture(new LobbyPlayerAi("first", null), 0);
        var ability = fixture.forest().getFirstSpellAbility();
        var activator = ability.getActivatingPlayer();
        PriorityRequest question = new PriorityRequest(fixture.player(), new StateReader(fixture.player(), 0L));
        assertSame(ability.getActivatingPlayer(), activator, "Candidate generation changed the native activator");
        assertThrows(IllegalArgumentException.class, () -> new PriorityRecorder(question, List.of()).encode());
        assertThrows(IllegalArgumentException.class, () -> new PriorityRecorder(question, List.of(choice.get(0))).encode());
        for (var player : List.of(stale.player(), repeated.player(), unbound.player(), bad.player(), broken.player())) {
            ((PlayerControllerTeacher) player.getController()).closeObservation();
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toRealPath();
        for (var entry : Map.of(Game.class, "forge-game", PlayerControllerAi.class, "forge-ai", MyRandom.class, "forge-core").entrySet()) {
            assertEquals(Path.of(entry.getKey().getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath(),
                    root.resolve(entry.getValue() + "/target/classes").toRealPath());
        }
        OriginalCompositeChoice.initialize(root);
        var test = new PlayerControllerTeacherTest();
        test.recordsFinalAnswersNeutrallyAndReplaysOnNewObjects();
        test.nativeFailuresLeavePendingQuestionsAndStopTeacher();
        test.priorityIsNeutralAndReplaysBeforeAndAfterLandExecution();
        test.priorityErrorsRemainDistinctFromAnswersAndExecution();
        System.out.println("PASS: selection teacher is neutral to native facts/RNG; final answers replay on new objects without AI; errors stay incomplete");
    }
}
