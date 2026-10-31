package forge.rl;

import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.*;

import static org.testng.Assert.*;

public class CastingPriorityTest {
    @BeforeClass public void initialize() { OriginalCompositeChoice.initialize(Path.of("..")); }

    private static final class Recording implements IDecisionRecordCallback {
        final List<Map<String, Object>> requests = new ArrayList<>();
        final List<List<Integer>> answers = new ArrayList<>();
        Map<String, Object> pending;
        Map<String, Object> execution;
        boolean executing;
        @Override public void before(Map<String, Object> request) { assertNull(pending); pending = request; }
        @Override public void after(List<Integer> answer) {
            requests.add(pending); answers.add(List.copyOf(answer)); pending = null;
        }
        @Override public void beginExecution() { assertFalse(executing); executing = true; }
        @Override public void executed(Map<String, Object> result) { assertTrue(executing); execution = result; executing = false; }
    }

    private static Player opponent(ManaPaymentBoundaryTest.Fixture f) { return f.game().getPlayers().get(1); }

    private record Result(List<Object> facts, List<Boolean> taps, List<String> pool,
                          List<String> spent, List<Integer> activated, long rng) { }
    private static Result run(ManaPaymentBoundaryTest.Fixture f) {
        var phase = f.game().getPhaseHandler();
        // Prepared MAIN2 fixture: enable the native priority loop, then stop before resolution.
        phase.onStackResolved();
        phase.mainLoopStep();
        assertSame(f.game().getStack().peekAbility(), f.spell());
        assertSame(phase.getPriorityPlayer(), opponent(f));
        assertNull(opponent(f).getController().chooseSpellAbilityToPlay());
        assertEquals(f.game().getStack().size(), 1);
        assertTrue(f.spell().getHostCard().isInZone(ZoneType.Stack));
        assertFalse(f.game().isGameOver());
        var pool = new ArrayList<String>();
        f.player().getManaPool().forEach(mana -> pool.add(mana.toString()));
        return new Result(OriginalCompositeChoice.observe(f.game()), f.lands().stream().map(Card::isTapped).toList(), pool,
                f.spell().getPayingMana().stream().map(mana -> mana.toString()).toList(),
                f.spell().getPayingManaAbilities().stream().map(sa -> f.lands().indexOf(sa.getHostCard())).toList(),
                MyRandom.getRandom().nextLong());
    }

    private static void close(ManaPaymentBoundaryTest.Fixture f) {
        for (Player p : f.game().getPlayers()) {
            if (p.getController() instanceof PlayerControllerRl rl) { rl.closeObservation(); }
            if (p.getController() instanceof PlayerControllerTeacher teacher) { teacher.closeObservation(); }
        }
    }

    @Test public void nativeCastingTeacherAndReplayKeepChoicePaymentAndResultSeparate() {
        for (boolean floating : List.of(false, true)) {
            var baseline = ManaPaymentBoundaryTest.fixture(new LobbyPlayerAi("first", null), floating);
            opponent(baseline).dangerouslySetController(new PlayerControllerAi(baseline.game(), opponent(baseline), new LobbyPlayerAi("second", null)));
            Result expected = run(baseline);
            Recording recording = new Recording();
            var teacher = ManaPaymentBoundaryTest.fixture(new LobbyPlayerTeacher("first", recording), floating);
            opponent(teacher).dangerouslySetController(new PlayerControllerTeacher(teacher.game(), opponent(teacher), new LobbyPlayerAi("second", null), recording));
            assertEquals(run(teacher), expected, "Teacher changed native casting facts or RNG");
            assertEquals(recording.requests.size(), 4);
            assertNull(recording.pending);
            assertFalse(recording.executing);
            assertEquals(((Map<?, ?>) recording.requests.get(0).get("options")).get("kind"), "priority");
            assertEquals(((Map<?, ?>) recording.requests.get(1).get("options")).get("kind"), "mana_payment");
            var result = (Map<?, ?>) recording.execution.get("options");
            assertEquals(result.get("reported"), true);
            assertEquals(result.get("onStack"), true);
            assertEquals(recording.answers.subList(2, 4), List.of(List.of(0), List.of(0)));
            int[] position = {0};
            int[] executions = {0};
            IPlayerDecisionCallback callback = new IPlayerDecisionCallback() {
                @Override public List<Integer> choose(Map<String, Object> request) {
                    assertEquals(request, recording.requests.get(position[0]));
                    return recording.answers.get(position[0]++);
                }
                @Override public void executed(Map<String, Object> value) {
                    assertEquals(position[0], 2);
                    assertEquals(value, recording.execution);
                    executions[0]++;
                }
            };
            var replay = ManaPaymentBoundaryTest.fixture(new LobbyPlayerRl("first", callback), floating);
            opponent(replay).dangerouslySetController(new PlayerControllerRl(replay.game(), opponent(replay), new LobbyPlayerAi("second", null), callback));
            assertNotSame(teacher.spell(), replay.spell());
            Result applied = run(replay);
            assertEquals(applied.facts(), expected.facts());
            assertEquals(applied.taps(), expected.taps());
            assertEquals(applied.pool(), expected.pool());
            assertEquals(applied.spent(), expected.spent());
            assertEquals(applied.activated(), expected.activated());
            assertEquals(position[0], 4);
            assertEquals(executions[0], 1);
            close(teacher); close(replay);
        }
    }

    private static int option(Map<String, Object> request, String type, String name) {
        var items = (List<?>) ((Map<?, ?>) request.get("options")).get("items");
        for (int i = 1; i < items.size(); i++) {
            var item = (Map<?, ?>) items.get(i);
            if (type.equals(item.get("type")) && name.equals(item.get("name"))) { return i; }
        }
        throw new AssertionError("Missing native priority option " + type + " " + name);
    }

    @Test public void clientCanPlayLandFloatBasicManaThenSpendItInNativeCast() {
        int[] step = {0};
        var f = ManaPaymentBoundaryTest.fixture(new LobbyPlayerRl("first", request -> {
            var options = (Map<?, ?>) request.get("options");
            if (options.get("kind").equals("mana_payment")) {
                assertEquals(options.get("pool"), List.of(2));
                return List.of(1, 1, 0, 0, 2);
            }
            return List.of(switch (step[0]++) {
                case 0 -> option(request, "play_land", "Forest");
                case 1 -> option(request, "activate_mana", "Island");
                case 2 -> option(request, "cast_spell", "Grizzly Bears");
                default -> 0;
            });
        }), false);
        OriginalCompositeChoice.add(f.game(), f.player(), "Forest");
        var controller = f.player().getController();
        for (int i = 0; i < 3; i++) {
            var chosen = controller.chooseSpellAbilityToPlay();
            assertEquals(chosen.size(), 1);
            assertTrue(controller.playChosenSpellAbility(chosen.get(0)));
        }
        assertEquals(f.player().getLandsPlayedThisTurn(), 1);
        assertTrue(f.lands().get(2).isTapped());
        assertEquals(f.player().getManaPool().totalMana(), 0);
        assertSame(f.game().getStack().peekAbility(), f.spell());
        assertNull(controller.chooseSpellAbilityToPlay());
        close(f);
    }

    @Test public void exactVariantsStaleTimingAndCancelledCastStayExplicit() {
        var f = ManaPaymentBoundaryTest.fixture(new LobbyPlayerRl("first", request -> List.of(option(request, "cast_spell", "Grizzly Bears"))), false);
        SpellAbility chosen = f.player().getController().chooseSpellAbilityToPlay().get(0);
        assertThrows(IllegalArgumentException.class, () -> f.player().getController().playChosenSpellAbility(chosen.copy()));
        assertTrue(f.game().getStack().isEmpty()); close(f);
        var stale = ManaPaymentBoundaryTest.fixture(new LobbyPlayerRl("first", request -> List.of(option(request, "cast_spell", "Grizzly Bears"))), false);
        SpellAbility staleChoice = stale.player().getController().chooseSpellAbilityToPlay().get(0);
        stale.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_BEGIN, stale.player(), false, 6);
        SpellAbility selected = staleChoice;
        assertThrows(IllegalArgumentException.class, () -> stale.player().getController().playChosenSpellAbility(selected));
        assertTrue(stale.game().getStack().isEmpty()); close(stale);
        List<Map<String, Object>> results = new ArrayList<>();
        var cancelled = ManaPaymentBoundaryTest.fixture(new LobbyPlayerRl("first", new IPlayerDecisionCallback() {
            @Override public List<Integer> choose(Map<String, Object> request) {
                return ((Map<?, ?>) request.get("options")).get("kind").equals("mana_payment")
                        ? List.of(0, 0) : List.of(option(request, "cast_spell", "Grizzly Bears"));
            }
            @Override public void executed(Map<String, Object> result) { results.add(result); }
        }), false);
        SpellAbility spell = cancelled.player().getController().chooseSpellAbilityToPlay().get(0);
        assertFalse(cancelled.player().getController().playChosenSpellAbility(spell));
        assertTrue(cancelled.game().getStack().isEmpty());
        assertTrue(cancelled.game().getCardState(spell.getHostCard()).isInZone(ZoneType.Hand));
        assertEquals(((Map<?, ?>) results.get(0).get("options")).get("reported"), false);
        assertEquals(((Map<?, ?>) results.get(0).get("options")).get("onStack"), false);
        close(cancelled);
    }

    public static void main(String[] args) {
        OriginalCompositeChoice.initialize(Path.of(args[0]));
        var test = new CastingPriorityTest();
        test.nativeCastingTeacherAndReplayKeepChoicePaymentAndResultSeparate();
        test.clientCanPlayLandFloatBasicManaThenSpendItInNativeCast();
        test.exactVariantsStaleTimingAndCancelledCastStayExplicit();
        System.out.println("PASS: native priority casting, payment, floating basic mana, occupied-stack passes and replay verified");
    }
}
