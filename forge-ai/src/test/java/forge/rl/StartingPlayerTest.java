package forge.rl;

import com.google.common.eventbus.Subscribe;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.event.GameEventGameStarted;
import forge.game.player.Player;
import forge.game.player.PlayerController;
import forge.game.player.RegisteredPlayer;
import forge.game.phase.PhaseType;
import forge.game.zone.ZoneType;
import forge.rl.decision.context.DecisionExecutor;
import forge.rl.decision.context.StartingPlayerContext;
import forge.rl.decision.context.StartingPlayerExecutor;
import forge.util.MyRandom;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BiFunction;

import static org.testng.Assert.*;

public class StartingPlayerTest {
    @BeforeClass public void initialize() { OriginalCompositeChoice.initialize(Path.of("..")); }

    private static final class BeforeHands extends RuntimeException { }

    private static Game fixture(BiFunction<Player, LobbyPlayerAi, PlayerController> controller) {
        return fixture(controller, new GameRules(GameType.Constructed), 2);
    }

    private static Game fixture(BiFunction<Player, LobbyPlayerAi, PlayerController> controller, GameRules rules, int count) {
        List<RegisteredPlayer> seats = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String name = "player " + i;
            var lobby = new LobbyPlayerAi(name, null) {
                @Override public Player createIngamePlayer(Game game, int id) {
                    Player player = new Player(getName(), game, id) {
                        // Stop after the real GameStarted event, before drawing or asking for mulligans.
                        @Override public int getStartingHandSize() { throw new BeforeHands(); }
                    };
                    player.setFirstController(controller.apply(player, this));
                    return player;
                }
            };
            var seat = new RegisteredPlayer(new Deck(name)).setPlayer(lobby);
            seat.setId(17 + 14 * seats.size());
            seats.add(seat);
        }
        Game game = new Match(rules, seats, "play or draw").createGame();
        game.getRegisteredPlayers().forEach(p -> p.setLife(20, null));
        return game;
    }

    public static final class Started {
        int first = -1;
        @Subscribe public void started(GameEventGameStarted event) { first = event.firstTurn().getId(); }
    }

    private record Outcome(int first, Map<String, Object> state, long randomAfter) { }

    private static Outcome start(Game game, long seed) {
        Started event = new Started();
        game.subscribeToEvents(event);
        MyRandom.setRandom(new Random(seed));
        assertThrows(BeforeHands.class, () -> game.getAction().startGame(null));
        assertTrue(event.first >= 0, "Native startup must consume the selected Player");
        assertNull(game.getPhaseHandler().getPhase());
        assertNull(game.getStartingPlayer(), "The first turn has not started yet");
        assertTrue(game.getCardsIn(ZoneType.Hand).isEmpty());
        assertFalse(game.isGameOver(), "A test stop is not a game outcome");
        var reader = new StateReader(game.getRegisteredPlayers().get(0), 0L);
        try { return new Outcome(event.first, reader.snapshot(), MyRandom.getRandom().nextLong()); }
        finally { reader.close(); }
    }

    private static void close(Game game) {
        for (Player p : game.getRegisteredPlayers()) {
            if (p.getController() instanceof PlayerControllerRl rl) { rl.closeObservation(); }
            if (p.getController() instanceof PlayerControllerTeacher teacher) { teacher.closeObservation(); }
        }
    }

    @Test public void nativeStartupWaitsWithoutOpponentQuestionAndAppliesPlayOrDraw() {
        for (boolean draw : List.of(false, true)) {
            int[] observed = {0};
            int[] calls = {0};
            int[] chosen = {-1};
            Game game = fixture((p, lobby) -> new PlayerControllerRl(p.getGame(), p, lobby, new IPlayerDecisionCallback() {
                @Override public void observe(StateReader reader) { observed[0]++; }
                @Override public List<Integer> choose(Map<String, Object> request) {
                    calls[0]++;
                    assertEquals(observed[0], 2, "Waiting controller must observe before the decision");
                    var options = (Map<?, ?>) request.get("question");
                    assertEquals(options.get("kind"), "starting_player");
                    assertEquals(options.get("isFirstGame"), true);
                    var state = (Map<?, ?>) request.get("state");
                    assertNull(((Map<?, ?>) state.get("game")).get("phase"));
                    assertEquals(((Map<?, ?>) state.get("game")).get("active_player"), "none");
                    assertEquals(((Map<?, ?>) state.get("game")).get("observer"), Map.of("kind", "player", "id", p.getId()));
                    assertFalse(((Map<?, ?>) state.get("game")).containsKey("starting_life"));
                    assertFalse(((Map<?, ?>) state.get("game")).containsKey("is_first_turn"));
                    assertFalse(((Map<?, ?>) state.get("game")).containsKey("day_time"));
                    assertEquals(((Map<?, ?>) state.get("game")).get("day_night"), "neither");
                    assertTrue(((List<?>) state.get("cards")).isEmpty());
                    Player first = draw ? p.getSingleOpponent() : p;
                    chosen[0] = first.getId();
                    return List.of(p.getGame().getPlayers().indexOf(first));
                }
            }));
            assertEquals(start(game, 42).first(), chosen[0]);
            assertEquals(calls[0], 1, "Waiting is a service, not a decision");
            close(game);
        }
    }

    private static class Recording implements IDecisionRecordCallback {
        Map<String, Object> pending;
        Map<String, Object> request;
        List<Integer> answer;
        int beforeCalls;
        int afterCalls;
        @Override public void before(Map<String, Object> data) { assertNull(pending); pending = data; beforeCalls++; }
        @Override public void after(List<Integer> result) {
            assertNotNull(pending);
            request = pending;
            answer = List.copyOf(result);
            pending = null;
            afterCalls++;
        }
    }

    private static Game contractFixture(BiFunction<Player, LobbyPlayerAi, PlayerController> controller, String context) {
        var rules = new GameRules(context.equals("limited") ? GameType.Draft : GameType.Constructed);
        if (context.equals("variant")) { rules.addAppliedVariant(GameType.Commander); }
        Game game = fixture(controller, rules, 2);
        if (context.equals("variant")) {
            for (Player player : game.getPlayers()) {
                OriginalCompositeChoice.add(game, player, "Grizzly Bears");
                var commander = OriginalCompositeChoice.add(game, player, "Isamaru, Hound of Konda");
                commander.setCommander(true);
                game.getAction().moveTo(ZoneType.Command, commander, null, null);
            }
        }
        if (context.equals("play") || context.equals("restart")) {
            game.setAge(context.equals("play") ? GameStage.Play : GameStage.RestartedByKarn);
            game.getPhaseHandler().devModeSet(PhaseType.MAIN2, game.getPlayers().get(0), false, 6);
        }
        return game;
    }

    private static Map<String, Object> snapshot(Game game, int actor) {
        var reader = new StateReader(game.getPlayers().get(actor), 0L);
        try { return reader.snapshot(); }
        finally { reader.close(); }
    }

    @Test public void startingChoiceDependsOnPlayersAndFirstGameRatherThanFixtureContext() {
        for (String context : List.of("limited", "variant", "play", "restart")) {
            for (int actor : List.of(0, 1)) {
                for (boolean firstGame : List.of(false, true)) {
                    Game baseline = contractFixture((p, lobby) -> new PlayerControllerAi(p.getGame(), p, lobby), context);
                    Map<String, Object> before = snapshot(baseline, actor);
                    MyRandom.setRandom(new Random(42));
                    assertSame(baseline.getPlayers().get(actor).getController().chooseStartingPlayer(firstGame), baseline.getPlayers().get(actor));
                    long randomAfter = MyRandom.getRandom().nextLong();
                    assertEquals(snapshot(baseline, actor), before);
                    Recording recording = new Recording();
                    Game teacher = contractFixture((p, lobby) -> new PlayerControllerTeacher(p.getGame(), p, lobby, recording), context);
                    MyRandom.setRandom(new Random(42));
                    assertSame(teacher.getPlayers().get(actor).getController().chooseStartingPlayer(firstGame), teacher.getPlayers().get(actor));
                    assertEquals(MyRandom.getRandom().nextLong(), randomAfter);
                    assertEquals(snapshot(teacher, actor), before);
                    assertEquals(recording.request.get("state"), before);
                    assertEquals(((Map<?, ?>) recording.request.get("question")).get("isFirstGame"), firstGame);
                    assertEquals(recording.beforeCalls, 1);
                    assertEquals(recording.afterCalls, 1);
                    assertEquals(recording.answer, List.of(actor));
                    Game replay = contractFixture((p, lobby) -> new PlayerControllerRl(p.getGame(), p, lobby, request -> {
                        assertEquals(request, recording.request);
                        return recording.answer;
                    }), context);
                    MyRandom.setRandom(new Random(42));
                    assertSame(replay.getPlayers().get(actor).getController().chooseStartingPlayer(firstGame), replay.getPlayers().get(actor));
                    assertEquals(MyRandom.getRandom().nextLong(), randomAfter);
                    assertEquals(snapshot(replay, actor), before);
                    close(teacher);
                    close(replay);
                }
            }
        }
    }

    @Test public void nonDuelObservationAndNestedStartingChoicesStopBeforeAnotherQuestion() {
        for (int count : List.of(1, 3)) {
            for (boolean teacher : List.of(false, true)) {
                Game game = fixture((p, lobby) -> teacher
                        ? new PlayerControllerTeacher(p.getGame(), p, lobby, new Recording() {
                            @Override public void before(Map<String, Object> data) { fail("Non-duel reached recorder"); }
                        })
                        : new PlayerControllerRl(p.getGame(), p, lobby, request -> { throw new AssertionError("Non-duel reached client"); }),
                        new GameRules(GameType.Constructed), count);
                var controller = game.getPlayers().get(0).getController();
                assertThrows(UnsupportedOperationException.class, () -> controller.chooseStartingPlayer(true));
                if (!teacher) { assertThrows(UnsupportedOperationException.class, controller::awaitNextInput); }
                close(game);
            }
        }
        Game[] nested = {null};
        Recording recording = new Recording() {
            @Override public void before(Map<String, Object> data) {
                super.before(data);
                nested[0].getPlayers().get(0).getController().chooseStartingPlayer(false);
            }
        };
        nested[0] = fixture((p, lobby) -> new PlayerControllerTeacher(p.getGame(), p, lobby, recording));
        var controller = nested[0].getPlayers().get(0).getController();
        assertThrows(IllegalStateException.class, () -> controller.chooseStartingPlayer(true));
        assertEquals(recording.beforeCalls, 1);
        assertEquals(recording.afterCalls, 0);
        assertNotNull(recording.pending);
        close(nested[0]);

        for (boolean teacher : List.of(false, true)) {
            Recording pending = new Recording();
            int[] clientCalls = {0};
            Game priority = contractFixture((p, lobby) -> teacher
                    ? new PlayerControllerTeacher(p.getGame(), p, lobby, pending)
                    : new PlayerControllerRl(p.getGame(), p, lobby, request -> { clientCalls[0]++; return List.of(1); }), "play");
            OriginalCompositeChoice.add(priority, priority.getPlayers().get(0), "Forest");
            priority.getPhaseHandler().setPriority(priority.getPlayers().get(0));
            var priorityController = priority.getPlayers().get(0).getController();
            assertNotNull(priorityController.chooseSpellAbilityToPlay());
            assertThrows(IllegalStateException.class, () -> priorityController.chooseStartingPlayer(true));
            if (!teacher) { assertThrows(IllegalStateException.class, priorityController::awaitNextInput); }
            assertEquals(teacher ? pending.beforeCalls : clientCalls[0], 1,
                    "Pending execution must not open a starting-player question");
            close(priority);
        }
    }

    @Test public void startupScopeIsRestoredWhenCallbacksThrow() {
        for (String stage : List.of("observe", "before", "after")) {
            List<String> events = new ArrayList<>();
            var expected = new IllegalStateException("Failed " + stage);
            Game game = fixture((p, lobby) -> new PlayerControllerTeacher(p.getGame(), p, lobby, new IDecisionRecordCallback() {
                private boolean failed;
                private void visit(String current) {
                    events.add(current);
                    if (failed) { return; }
                    if (current.equals(stage)) { failed = true; throw expected; }
                }
                @Override public void observe(StateReader reader) { visit("observe"); }
                @Override public void before(Map<String, Object> request) { visit("before"); }
                @Override public void after(List<Integer> answer) { visit("after"); }
            }));
            try {
                var controller = game.getPlayers().get(0).getController();
                assertSame(expectThrows(IllegalStateException.class, () -> controller.chooseStartingPlayer(true)), expected);
                List<String> firstAttempt = switch (stage) {
                    case "observe" -> List.of("observe");
                    case "before" -> List.of("observe", "before");
                    case "after" -> List.of("observe", "before", "after");
                    default -> throw new AssertionError(stage);
                };
                assertEquals(events, firstAttempt, "Failed callback fabricated an answer or continued execution");
                events.clear();
                assertSame(controller.chooseStartingPlayer(false), game.getPlayers().get(0));
                assertEquals(events, stage.equals("observe") ? List.of("observe", "before", "after") : List.of("before", "after"));
            } finally { close(game); }
        }
    }

    @Test public void startupIgnoresOtherDecisionFailuresButRejectsClosedObservation() {
        Recording recording = new Recording();
        Game game = fixture((p, lobby) -> new PlayerControllerTeacher(p.getGame(), p, lobby, recording));
        var controller = game.getPlayers().get(0).getController();
        try {
            var previous = expectThrows(IllegalArgumentException.class, () -> controller.playChosenSpellAbility(null));
            assertSame(controller.chooseStartingPlayer(true), game.getPlayers().get(0));
            assertNull(recording.pending);
            assertEquals(recording.beforeCalls, 1);
            assertEquals(recording.afterCalls, 1);
            assertSame(expectThrows(IllegalArgumentException.class, controller::chooseSpellAbilityToPlay), previous,
                    "Other boundaries must keep their current error behavior");
        } finally { close(game); }
        assertThrows(IllegalStateException.class, () -> controller.chooseStartingPlayer(true));
        assertEquals(recording.beforeCalls, 1, "Closed observation must not open a question");
        assertEquals(recording.afterCalls, 1);
    }

    @Test public void adapterInterfacesKeepBindingsLocalToEachGame() {
        Game teacher = fixture((p, lobby) -> new PlayerControllerAi(p.getGame(), p, lobby));
        Game replay = fixture((p, lobby) -> new PlayerControllerAi(p.getGame(), p, lobby));
        var teacherView = new StateReader(teacher.getRegisteredPlayers().get(0), 0L);
        var replayView = new StateReader(replay.getRegisteredPlayers().get(0), 0L);
        try {
            var recorded = new StartingPlayerContext(teacher.getRegisteredPlayers().get(0), true);
            var current = new StartingPlayerContext(replay.getRegisteredPlayers().get(0), true);
            Player original = teacher.getPlayers().get(1);
            Player replacement = replay.getPlayers().get(1);
            List<Integer> answer = recorded.encodeAnswer(original);
            assertEquals(answer, List.of(1));
            assertThrows(UnsupportedOperationException.class, () -> answer.add(0));
            DecisionExecutor<Player> executor = new StartingPlayerExecutor(current, answer);
            assertNotSame(original, replacement);
            assertSame(executor.apply(), replacement);
            assertThrows(IllegalArgumentException.class, () -> recorded.encodeAnswer(replacement));
            assertThrows(IllegalArgumentException.class, () -> new StartingPlayerExecutor(current, List.of(0, 1)).apply());
            assertEquals(recorded.encodeAnswer(teacher.getPlayers().get(0)), List.of(0), "Recorder retained a previous answer");
            assertEquals(current.encodeAnswer(replacement), answer, "Recorder retained a previous request");
            assertThrows(IllegalArgumentException.class, () -> current.encodeAnswer(original));
            assertEquals(recorded.encodeAnswer(original), answer, "Replay changed the original recorder's bindings");
        } finally { teacherView.close(); replayView.close(); }
    }

    @Test public void teacherKeepsOriginalFinalPlayerStateAndRngAndReplaysOnNewPlayers() {
        for (long seed : List.of(0L, 42L, 100L)) {
            Game baseline = fixture((p, lobby) -> new PlayerControllerAi(p.getGame(), p, lobby));
            Outcome expected = start(baseline, seed);
            Recording recording = new Recording();
            Game teacher = fixture((p, lobby) -> new PlayerControllerTeacher(p.getGame(), p, lobby, recording));
            assertEquals(start(teacher, seed), expected, "Teacher changed the original player, state or RNG");
            assertEquals(recording.beforeCalls, 1);
            assertEquals(recording.afterCalls, 1);
            assertNull(recording.pending);
            assertEquals(teacher.getPlayers().get(recording.answer.get(0)).getId(), expected.first(), "AI's final answer is itself");
            int[] replayed = {0};
            Game replay = fixture((p, lobby) -> new PlayerControllerRl(p.getGame(), p, lobby, request -> {
                assertEquals(request, recording.request);
                replayed[0]++;
                return recording.answer;
            }));
            assertNotSame(teacher.getRegisteredPlayers().get(0), replay.getRegisteredPlayers().get(0));
            assertEquals(start(replay, seed), expected);
            assertEquals(replayed[0], 1);
            close(teacher);
            close(replay);
        }
    }

    @Test public void invalidResponsesAndRecorderFailuresStopWithoutInventingAnAnswer() {
        for (List<Integer> invalid : List.of(List.<Integer>of(), List.of(-1), List.of(2), List.of(0, 1))) {
            Game game = fixture((p, lobby) -> new PlayerControllerRl(p.getGame(), p, lobby, request -> invalid));
            assertThrows(IllegalArgumentException.class, () -> game.getAction().startGame(null));
            assertNull(game.getStartingPlayer());
            assertNull(game.getPhaseHandler().getPhase());
            assertFalse(game.isGameOver());
            Player failed = game.getRegisteredPlayers().stream().filter(p -> {
                try { p.getController().awaitNextInput(); return false; }
                catch (IllegalArgumentException error) { return true; }
            }).findFirst().orElseThrow();
            assertThrows(IllegalArgumentException.class, () -> failed.getController().chooseStartingPlayer(true));
            close(game);
        }
        Recording recording = new Recording() {
            @Override public void after(List<Integer> answer) { throw new IllegalStateException("recorder failed"); }
        };
        Game game = fixture((p, lobby) -> new PlayerControllerTeacher(p.getGame(), p, lobby, recording));
        assertThrows(IllegalStateException.class, () -> game.getAction().startGame(null));
        assertNotNull(recording.pending);
        assertEquals(((Map<?, ?>) recording.pending.get("question")).get("isFirstGame"), true);
        assertNull(recording.answer);
        assertEquals(recording.beforeCalls, 1);
        assertNull(game.getStartingPlayer());
        assertNull(game.getPhaseHandler().getPhase());
        assertFalse(game.isGameOver());
        close(game);

        int[] attempts = {0};
        Game failedWait = fixture((p, lobby) -> new PlayerControllerRl(p.getGame(), p, lobby, new IPlayerDecisionCallback() {
            @Override public void observe(StateReader reader) { attempts[0]++; throw new IllegalStateException("observe failed"); }
            @Override public List<Integer> choose(Map<String, Object> request) { throw new AssertionError("Failed observer asked a question"); }
        }));
        var failedController = failedWait.getRegisteredPlayers().get(0).getController();
        assertThrows(IllegalStateException.class, failedController::awaitNextInput);
        assertThrows(IllegalStateException.class, failedController::awaitNextInput);
        assertThrows(IllegalStateException.class, () -> failedController.chooseStartingPlayer(true));
        assertEquals(attempts[0], 1, "Failed observation must not be rebound");
        close(failedWait);
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toRealPath();
        for (var entry : Map.of(PlayerControllerAi.class, "forge-ai", Game.class, "forge-game", MyRandom.class, "forge-core").entrySet()) {
            assertEquals(Path.of(entry.getKey().getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath(),
                    root.resolve(entry.getValue() + "/target/classes").toRealPath());
        }
        OriginalCompositeChoice.initialize(root);
        var test = new StartingPlayerTest();
        test.startingChoiceDependsOnPlayersAndFirstGameRatherThanFixtureContext();
        test.nonDuelObservationAndNestedStartingChoicesStopBeforeAnotherQuestion();
        test.startupScopeIsRestoredWhenCallbacksThrow();
        test.startupIgnoresOtherDecisionFailuresButRejectsClosedObservation();
        test.adapterInterfacesKeepBindingsLocalToEachGame();
        test.nativeStartupWaitsWithoutOpponentQuestionAndAppliesPlayOrDraw();
        test.teacherKeepsOriginalFinalPlayerStateAndRngAndReplaysOnNewPlayers();
        test.invalidResponsesAndRecorderFailuresStopWithoutInventingAnAnswer();
        System.out.println("PASS: native starting player, wait service, teacher state/RNG and replay verified");
    }
}
