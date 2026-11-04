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
import forge.rl.decisions.DecisionExecutor;
import forge.rl.decisions.StartingPlayerRequest;
import forge.rl.decisions.StartingPlayerExecutor;
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
        try (var reader = new StateReader(game.getPlayers().get(actor), 0L)) {
            return reader.snapshot();
        }
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
                    Game replay = contractFixture((p, lobby) -> new PlayerControllerRl(p.getGame(), p, lobby, req -> {
                        assertEquals(req, recording.request);
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

    @Test public void adapterInterfacesKeepBindingsLocalToEachGame() {
        Game teacher = fixture((p, lobby) -> new PlayerControllerAi(p.getGame(), p, lobby));
        Game replay = fixture((p, lobby) -> new PlayerControllerAi(p.getGame(), p, lobby));
        try {
            var recorded = new StartingPlayerRequest(teacher.getRegisteredPlayers().get(0), true);
            var current = new StartingPlayerRequest(replay.getRegisteredPlayers().get(0), true);
            Player original = teacher.getPlayers().get(1);
            Player replacement = replay.getPlayers().get(1);
            List<Integer> result = recorded.encode(original);
            assertEquals(result, List.of(1));
            DecisionExecutor<Player> executor = new StartingPlayerExecutor(current, result);
            assertNotSame(original, replacement);
            assertSame(executor.apply(), replacement);
            assertThrows(IllegalArgumentException.class, () -> recorded.encode(replacement));
            assertEquals(recorded.encode(teacher.getPlayers().get(0)), List.of(0));
            assertEquals(current.encode(replacement), result);
        } finally { close(teacher); close(replay); }
    }
}
