package forge.rl;

import com.google.common.eventbus.Subscribe;
import forge.MulliganDefs;
import forge.StaticData;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.*;
import forge.game.event.GameEventPlayerPriority;
import forge.game.player.*;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.*;
import java.util.function.BiFunction;

import static org.testng.Assert.*;

public class NaturalTurnsTest {
    @BeforeClass public void initialize() {
        OriginalCompositeChoice.initialize(Path.of(".."));
        StaticData.instance().setFilteredHandsEnabled(false);
        StaticData.instance().setMulliganRule(MulliganDefs.MulliganRule.London);
    }

    private static Game fixture(BiFunction<Player, LobbyPlayerAi, PlayerController> factory, int opening) {
        List<RegisteredPlayer> seats = new ArrayList<>();
        for (String name : List.of("first", "second")) {
            var lobby = new LobbyPlayerAi(name, null) {
                @Override public Player createIngamePlayer(Game game, int id) {
                    Player p = new Player(getName(), game, id);
                    p.setFirstController(factory.apply(p, this));
                    return p;
                }
            };
            Deck deck = new Deck(name);
            for (String card : List.of("Forest", "Island")) {
                deck.getMain().add(StaticData.instance().getCommonCards().getCard(card), 30);
            }
            seats.add(new RegisteredPlayer(deck).setPlayer(lobby));
        }
        Game game = new Match(new GameRules(GameType.Constructed), seats, "natural turns").createGame();
        for (Player p : game.getPlayers()) {
            p.setLife(20, null);
            // Nine cards exercise the unchanged teacher's cleanup without forcing it to pass.
            p.setStartingHandSize(opening);
            for (int i = 0; i < 60; i++) {
                var paper = StaticData.instance().getCommonCards().getCard(i % 2 == 0 ? "Forest" : "Island");
                p.getZone(ZoneType.Library).add(CardFactory.getCard(paper, p, game));
            }
        }
        return game;
    }

    private static final class AtBoundary extends RuntimeException { }
    public static final class Boundary {
        final Game game;
        final List<List<Object>> trace = new ArrayList<>();
        Boundary(Game game) { this.game = game; }
        @Subscribe public void priority(GameEventPlayerPriority event) {
            trace.add(OriginalCompositeChoice.observe(game));
            if (game.getPhaseHandler().getTurn() < 5) { return; }
            Player p = game.getPhaseHandler().getPriorityPlayer();
            p.dangerouslySetController(new PlayerControllerAi(game, p, new LobbyPlayerAi("stop", null)) {
                @Override public List<SpellAbility> chooseSpellAbilityToPlay() { throw new AtBoundary(); }
            });
        }
    }
    private record Outcome(List<List<Object>> trace, List<Object> facts, long random) { }
    private static Outcome run(Game game) {
        MyRandom.setRandom(new Random(42));
        Boundary boundary = new Boundary(game);
        game.subscribeToEvents(boundary);
        var controllers = game.getPlayers().stream().map(Player::getController).toList();
        try { assertThrows(AtBoundary.class, () -> game.getAction().startGame(null)); }
        finally {
            for (int i = 0; i < controllers.size(); i++) { game.getPlayers().get(i).dangerouslySetController(controllers.get(i)); }
        }
        assertEquals(game.getPhaseHandler().getTurn(), 5);
        assertSame(game.getPhaseHandler().getPlayerTurn(), game.getStartingPlayer());
        assertFalse(game.isGameOver());
        return new Outcome(boundary.trace, OriginalCompositeChoice.observe(game), MyRandom.getRandom().nextLong());
    }
    private static final class Recording implements IDecisionRecordCallback {
        final List<Map<String, Object>> requests = new ArrayList<>();
        final List<List<Integer>> answers = new ArrayList<>();
        Map<String, Object> pending;
        @Override public void before(Map<String, Object> request) { assertNull(pending); pending = request; }
        @Override public void after(List<Integer> answer) {
            requests.add(pending); answers.add(List.copyOf(answer)); pending = null;
        }
    }
    private static void close(Game game) {
        for (Player p : game.getPlayers()) {
            if (p.getController() instanceof PlayerControllerRl rl) { rl.closeObservation(); }
            if (p.getController() instanceof PlayerControllerTeacher teacher) { teacher.closeObservation(); }
        }
    }

    @Test public void nativeTeacherTurnsAndCleanupReplay() {
        for (int opening : List.of(7, 9)) {
            var baseline = fixture((p,l) -> new PlayerControllerAi(p.getGame(), p, l), opening);
            Outcome expected = run(baseline);
            Recording saved = new Recording();
            var teacher = fixture((p,l) -> new PlayerControllerTeacher(p.getGame(), p, l, saved), opening);
            Outcome recorded = run(teacher);
            assertTrue(recorded.trace().equals(expected.trace()), "Teacher changed native turn trace");
            assertTrue(recorded.facts().equals(expected.facts()), "Teacher changed final facts");
            assertEquals(recorded.random(), expected.random());
            assertNull(saved.pending);
            int discards = 0;
            for (Map<String, Object> request : saved.requests) {
                var options = (Map<?, ?>) request.get("options");
                if (options.get("kind").equals("discard_cleanup")) {
                    discards++;
                    assertEquals(options.get("min"), options.get("max"));
                    assertEquals(((Map<?, ?>) ((Map<?, ?>) request.get("state")).get("game")).get("phase"), "CLEANUP");
                }
            }
            assertEquals(discards > 0, opening == 9);
            for (Player p : teacher.getPlayers()) {
                assertEquals(p.getCardsIn(ZoneType.Battlefield).size(), 2);
                // The starting player skips its first draw; the other draws on both turns.
                assertEquals(p.getCardsIn(ZoneType.Library).size(), 60 - opening - (p == teacher.getStartingPlayer() ? 1 : 2));
            }
            int[] position = {0};
            var replay = fixture((p,l) -> new PlayerControllerRl(p.getGame(), p, l, request -> {
                assertEquals(request, saved.requests.get(position[0]));
                return saved.answers.get(position[0]++);
            }), opening);
            assertNotSame(teacher.getPlayers().get(0), replay.getPlayers().get(0));
            Outcome replayed = run(replay);
            assertTrue(replayed.trace().equals(expected.trace()), "Replay changed native turn trace");
            assertTrue(replayed.facts().equals(expected.facts()), "Replay changed final facts");
            assertEquals(position[0], saved.requests.size());
            close(teacher); close(replay);
        }
    }

    @Test public void ordinaryClientCanPassIntoCleanupAndInvalidDiscardStops() {
        int[] discards = {0};
        var game = fixture((p,l) -> new PlayerControllerRl(p.getGame(), p, l, request -> {
            var options = (Map<?, ?>) request.get("options");
            switch ((String) options.get("kind")) {
                case "starting_player": return List.of(0);
                case "mulligan_keep": return List.of(1);
                case "priority":
                    return List.of(p.getCardsIn(ZoneType.Battlefield).isEmpty()
                            && ((List<?>) options.get("items")).size() > 1 ? 1 : 0);
                case "discard_cleanup":
                    discards[0]++;
                    int count = (Integer) options.get("min");
                    return java.util.stream.IntStream.range(0, count).boxed().toList();
                default: throw new AssertionError(options);
            }
        }), 7);
        run(game);
        assertEquals(discards[0], 1);
        for (Player p : game.getPlayers()) {
            assertEquals(p.getCardsIn(ZoneType.Hand).size(), 7);
            assertEquals(p.getCardsIn(ZoneType.Battlefield).size(), 1);
        }
        assertEquals(game.getCardsIn(ZoneType.Graveyard).size(), 1);
        close(game);

        var failed = fixture((p,l) -> new PlayerControllerRl(p.getGame(), p, l, r -> List.of()), 7);
        Player p = failed.getPlayers().get(0);
        assertThrows(IllegalArgumentException.class, () -> p.getController().chooseCardsToDiscardToMaximumHandSize(1));
        assertThrows(IllegalArgumentException.class, () -> p.getController().autoPassCancel());
        assertFalse(failed.isGameOver()); close(failed);
    }

    public static void main(String[] args) {
        OriginalCompositeChoice.initialize(Path.of(args[0]));
        var test = new NaturalTurnsTest();
        StaticData.instance().setFilteredHandsEnabled(false);
        StaticData.instance().setMulliganRule(MulliganDefs.MulliganRule.London);
        test.nativeTeacherTurnsAndCleanupReplay();
        test.ordinaryClientCanPassIntoCleanupAndInvalidDiscardStops();
        System.out.println("PASS: native land turns, cleanup, teacher state/RNG and replay verified");
    }
}
