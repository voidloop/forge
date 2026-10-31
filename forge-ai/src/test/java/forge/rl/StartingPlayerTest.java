package forge.rl;

import com.google.common.eventbus.Subscribe;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.event.GameEventGameStarted;
import forge.game.player.Player;
import forge.game.player.PlayerController;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
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
        List<RegisteredPlayer> seats = new ArrayList<>();
        for (String name : List.of("first", "second")) {
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
        Game game = new Match(new GameRules(GameType.Constructed), seats, "play or draw").createGame();
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
                    var options = (Map<?, ?>) request.get("options");
                    assertEquals(options.get("kind"), "starting_player");
                    assertEquals(options.get("isFirstGame"), true);
                    var state = (Map<?, ?>) request.get("state");
                    assertNull(((Map<?, ?>) state.get("game")).get("phase"));
                    assertEquals(((Map<?, ?>) state.get("game")).get("active_player"), "none");
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
        Player player = game.getRegisteredPlayers().get(1);
        assertThrows(IllegalStateException.class, () -> player.getController().chooseStartingPlayer(false));
        assertNotNull(recording.pending);
        assertEquals(((Map<?, ?>) recording.pending.get("options")).get("isFirstGame"), false);
        assertNull(recording.answer);
        assertThrows(IllegalStateException.class, () -> player.getController().chooseStartingPlayer(false));
        assertThrows(IllegalStateException.class, () -> player.getController().awaitNextInput());
        assertEquals(recording.beforeCalls, 1);
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
        test.nativeStartupWaitsWithoutOpponentQuestionAndAppliesPlayOrDraw();
        test.teacherKeepsOriginalFinalPlayerStateAndRngAndReplaysOnNewPlayers();
        test.invalidResponsesAndRecorderFailuresStopWithoutInventingAnAnswer();
        System.out.println("PASS: native starting player, wait service, teacher state/RNG and replay verified");
    }
}
