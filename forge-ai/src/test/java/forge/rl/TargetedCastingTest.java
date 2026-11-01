package forge.rl;

import forge.LobbyPlayer;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.phase.PhaseType;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.*;

import static org.testng.Assert.*;

public class TargetedCastingTest {
    @BeforeClass public void initialize() { OriginalCompositeChoice.initialize(Path.of("..")); }

    static ManaPaymentBoundaryTest.Fixture fixture(LobbyPlayer lobby, boolean creature) {
        var f = ManaPaymentBoundaryTest.fixture(lobby, false);
        f.player().getZone(ZoneType.Hand).remove(f.spell().getHostCard());
        SpellAbility shock = OriginalCompositeChoice.add(f.game(), f.player(), "Shock").getFirstSpellAbility();
        shock.setActivatingPlayer(f.player());
        Player opponent = f.game().getPlayers().get(1);
        opponent.dangerouslySetController(new PlayerControllerAi(f.game(), opponent, new LobbyPlayerAi("second", null)));
        if (creature) {
            for (int i = 0; i < 2; i++) {
                Card bear = OriginalCompositeChoice.add(f.game(), opponent, "Grizzly Bears");
                opponent.getZone(ZoneType.Hand).remove(bear);
                opponent.getZone(ZoneType.Battlefield).add(bear);
            }
        } else { opponent.setLife(6, null); }
        f.game().getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, opponent, false, 6);
        f.game().getPhaseHandler().setPriority(f.player());
        return new ManaPaymentBoundaryTest.Fixture(f.game(), f.player(), shock, f.lands());
    }

    private static final class Boundary extends PlayerControllerAi {
        List<GameEntity> candidates;
        GameEntity selected;
        int calls;
        Boundary(Game game, Player player, LobbyPlayerAi lobby) { super(game, player, lobby); }
        @Override public List<SpellAbility> chooseSpellAbilityToPlay() {
            calls++;
            SpellAbility shock = player.getCardsIn(ZoneType.Hand).getFirst().getFirstSpellAbility();
            candidates = List.copyOf(shock.getTargetRestrictions().getAllCandidates(shock));
            List<SpellAbility> chosen = super.chooseSpellAbilityToPlay();
            assertNotNull(chosen);
            assertSame(chosen.get(0), shock);
            assertEquals(shock.getTargets().size(), 1);
            selected = shock.getTargets().getTargetEntities().iterator().next();
            assertTrue(candidates.stream().anyMatch(candidate -> candidate == selected));
            return chosen;
        }
    }

    @Test public void publicBoundaryCapturesPreviousAlternativesAndFinalOriginalTargetNeutrally() {
        for (boolean creature : List.of(false, true)) {
            var baseline = fixture(new LobbyPlayerAi("first", null), creature);
            assertTrue(baseline.spell().canPlay(), "Shock must be legal in the prepared fixture");
            var chosen = baseline.player().getController().chooseSpellAbilityToPlay();
            assertNotNull(chosen, "Original choice, creature=" + creature);
            var expected = OriginalCompositeChoice.observe(baseline.game());
            long rng = MyRandom.getRandom().nextLong();
            var recorded = fixture(new LobbyPlayerAi("first", null) {
                @Override public Player createIngamePlayer(Game game, int id) {
                    Player player = new Player(getName(), game, id);
                    player.setFirstController(new Boundary(game, player, this));
                    return player;
                }
            }, creature);
            recorded.player().getController().chooseSpellAbilityToPlay();
            Boundary boundary = (Boundary) recorded.player().getController();
            assertEquals(boundary.calls, 1);
            assertEquals(boundary.candidates.size(), creature ? 4 : 2);
            assertEquals(boundary.selected instanceof Card, creature);
            assertEquals(boundary.selected.getId(), ((GameEntity) chosen.get(0).getTargets().get(0)).getId());
            assertEquals(OriginalCompositeChoice.observe(recorded.game()), expected);
            assertEquals(MyRandom.getRandom().nextLong(), rng);
        }
    }

    private static final class Recording implements IDecisionRecordCallback {
        final List<Map<String, Object>> requests = new ArrayList<>();
        final List<List<Integer>> answers = new ArrayList<>();
        Map<String, Object> pending;
        Map<String, Object> execution;
        boolean executing;
        @Override public void before(Map<String, Object> request) { assertNull(pending); pending = request; }
        @Override public void after(List<Integer> answer) {
            assertNotNull(pending);
            requests.add(pending); answers.add(List.copyOf(answer)); pending = null;
        }
        @Override public void beginExecution() { assertFalse(executing); executing = true; }
        @Override public void executed(Map<String, Object> result) { assertTrue(executing); execution = result; executing = false; }
    }

    private record Outcome(List<Object> facts, List<String> spent, List<Integer> activated,
                           int target, List<String> events, long rng) { }

    private static Outcome run(ManaPaymentBoundaryTest.Fixture f, boolean invalidate) {
        var phase = f.game().getPhaseHandler();
        var opponent = f.game().getPlayers().get(1);
        var bears = List.copyOf(opponent.getCardsIn(ZoneType.Battlefield));
        var events = new CastingPriorityTest.Events(f);
        f.game().subscribeToEvents(events);
        phase.onStackResolved();
        phase.setPriority(f.player());
        phase.mainLoopStep();
        assertSame(f.game().getStack().peekAbility(), f.spell());
        assertEquals(f.spell().getTargets().size(), 1);
        GameEntity selected = f.spell().getTargets().getTargetEntities().iterator().next();
        int target = selected instanceof Player ? -1 : bears.indexOf(selected);
        assertTrue(target >= 0 || selected == opponent);
        var spent = f.spell().getPayingMana().stream().map(Object::toString).toList();
        assertEquals(spent, List.of("R"));
        var activated = f.spell().getPayingManaAbilities().stream().map(sa -> f.lands().indexOf(sa.getHostCard())).toList();
        assertEquals(activated, List.of(3));
        assertTrue(f.lands().get(3).isTapped());
        assertTrue(f.player().getManaPool().isEmpty());
        if (invalidate) {
            assertTrue(selected instanceof Card);
            f.game().getAction().moveToGraveyard((Card) selected, null, null);
        }
        phase.mainLoopStep();
        assertTrue(f.game().getStack().isEmpty());
        assertFalse(f.game().isGameOver());
        assertTrue(f.game().getCardState(f.spell().getHostCard()).isInZone(ZoneType.Graveyard));
        assertSame(phase.getPriorityPlayer(), opponent);
        phase.mainLoopStep();
        assertEquals(opponent.getLife(), selected instanceof Player ? 4 : 20);
        assertEquals(opponent.getCardsIn(ZoneType.Battlefield).size(), bears.isEmpty() ? 0 : 1);
        assertSame(phase.getPriorityPlayer(), f.player());
        assertTrue(events.trace.contains("resolved:" + invalidate));
        return new Outcome(OriginalCompositeChoice.observe(f.game()), spent, activated, target,
                List.copyOf(events.trace), MyRandom.getRandom().nextLong());
    }

    private static void close(ManaPaymentBoundaryTest.Fixture f) {
        for (Player player : f.game().getPlayers()) {
            if (player.getController() instanceof PlayerControllerRl rl) { rl.closeObservation(); }
            if (player.getController() instanceof PlayerControllerTeacher teacher) { teacher.closeObservation(); }
        }
    }

    @Test public void teacherAndClientReplayMatchNativeTargetsPaymentResolutionAndPriority() {
        for (boolean creature : List.of(false, true)) {
            for (boolean invalidate : creature ? List.of(false, true) : List.of(false)) {
                Outcome baseline = run(fixture(new LobbyPlayerAi("first", null), creature), invalidate);
                Recording recording = new Recording();
                var teacher = fixture(new LobbyPlayerTeacher("first", recording), creature);
                var opponent = teacher.game().getPlayers().get(1);
                opponent.dangerouslySetController(new PlayerControllerTeacher(teacher.game(), opponent,
                        new LobbyPlayerAi("second", null), recording));
                assertEquals(run(teacher, invalidate), baseline, "Teacher changed facts, final target, events or RNG");
                assertNull(recording.pending);
                assertFalse(recording.executing);
                assertEquals(recording.answers.get(0).size(), 2);
                assertEquals(((Map<?, ?>) recording.requests.get(1).get("options")).get("kind"), "mana_payment");
                assertEquals(recording.answers.get(1), List.of(1, 1, 3, 3));
                int[] position = {0};
                int[] executions = {0};
                IPlayerDecisionCallback callback = new IPlayerDecisionCallback() {
                    @Override public List<Integer> choose(Map<String, Object> request) {
                        assertEquals(request, recording.requests.get(position[0]));
                        return recording.answers.get(position[0]++);
                    }
                    @Override public void executed(Map<String, Object> value) {
                        assertEquals(value, recording.execution); executions[0]++;
                    }
                };
                var replay = fixture(new LobbyPlayerRl("first", callback), creature);
                opponent = replay.game().getPlayers().get(1);
                opponent.dangerouslySetController(new PlayerControllerRl(replay.game(), opponent,
                        new LobbyPlayerAi("second", null), callback));
                assertNotSame(replay.spell(), teacher.spell());
                Outcome applied = run(replay, invalidate);
                assertEquals(applied.facts(), baseline.facts());
                assertEquals(applied.spent(), baseline.spent());
                assertEquals(applied.activated(), baseline.activated());
                assertEquals(applied.target(), baseline.target());
                assertEquals(applied.events(), baseline.events());
                assertEquals(position[0], recording.requests.size());
                assertEquals(executions[0], 1);
                close(teacher); close(replay);
            }
        }
    }

    private static List<Integer> castAnswer(Map<String, Object> request, int target) {
        var items = (List<?>) ((Map<?, ?>) request.get("options")).get("items");
        for (int i = 1; i < items.size(); i++) {
            if ("cast_spell".equals(((Map<?, ?>) items.get(i)).get("type"))) { return List.of(i, target); }
        }
        throw new AssertionError("Missing targeted spell option");
    }

    @Test public void refusalTargetCancellationAndPaymentCancellationRemainDistinct() {
        var pass = fixture(new LobbyPlayerRl("first", request -> List.of(0)), true);
        assertNull(pass.player().getController().chooseSpellAbilityToPlay());
        assertTrue(pass.game().getStack().isEmpty()); close(pass);
        for (boolean cancelTarget : List.of(false, true)) {
            List<Map<String, Object>> results = new ArrayList<>();
            int[] payments = {0};
            var f = fixture(new LobbyPlayerRl("first", new IPlayerDecisionCallback() {
                @Override public List<Integer> choose(Map<String, Object> request) {
                    if ("mana_payment".equals(((Map<?, ?>) request.get("options")).get("kind"))) {
                        payments[0]++; return List.of(0, 0);
                    }
                    return castAnswer(request, cancelTarget ? -1 : 3);
                }
                @Override public void executed(Map<String, Object> result) { results.add(result); }
            }), true);
            var controller = f.player().getController();
            assertFalse(controller.playChosenSpellAbility(controller.chooseSpellAbilityToPlay().get(0)));
            assertEquals(payments[0], cancelTarget ? 0 : 1);
            assertEquals(results.size(), 1);
            var result = (Map<?, ?>) results.get(0).get("options");
            assertEquals(result.get("reported"), false);
            assertEquals(result.get("onStack"), false);
            assertTrue(f.game().getCardState(f.spell().getHostCard()).isInZone(ZoneType.Hand));
            assertTrue(f.lands().stream().noneMatch(Card::isTapped));
            assertTrue(f.player().getManaPool().isEmpty());
            assertFalse(f.game().getStack().isFrozen());
            close(f);
        }
    }

    @Test public void staleTargetsExactAbilitiesAndInvalidAnswersStopWithoutInventedResults() {
        for (boolean copy : List.of(false, true)) {
            List<Map<String, Object>> results = new ArrayList<>();
            var f = fixture(new LobbyPlayerRl("first", new IPlayerDecisionCallback() {
                @Override public List<Integer> choose(Map<String, Object> request) { return castAnswer(request, 3); }
                @Override public void executed(Map<String, Object> result) { results.add(result); }
            }), true);
            var controller = f.player().getController();
            var selected = controller.chooseSpellAbilityToPlay().get(0);
            if (!copy) {
                Card target = f.game().getPlayers().get(1).getCardsIn(ZoneType.Battlefield).get(1);
                f.game().getAction().moveToGraveyard(target, null, null);
            }
            RuntimeException failure = expectThrows(IllegalArgumentException.class,
                    () -> controller.playChosenSpellAbility(copy ? selected.copy() : selected));
            assertSame(expectThrows(IllegalArgumentException.class, controller::chooseSpellAbilityToPlay), failure);
            assertTrue(results.isEmpty());
            assertTrue(f.lands().stream().noneMatch(Card::isTapped));
            close(f);
        }
        for (List<Integer> answer : List.of(List.of(1), List.of(1, 4), List.of(1, -2), List.of(0, 1))) {
            var f = fixture(new LobbyPlayerRl("first", request -> answer), true);
            assertThrows(IllegalArgumentException.class, f.player().getController()::chooseSpellAbilityToPlay);
            assertTrue(f.game().getStack().isEmpty()); close(f);
        }
    }
}
