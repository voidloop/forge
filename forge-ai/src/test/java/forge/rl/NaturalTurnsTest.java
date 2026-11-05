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
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.rl.requests.CleanupRequests;
import forge.rl.requests.SelectionExecutor;
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
        return fixture(factory, opening, new GameRules(GameType.Constructed));
    }

    private static Game fixture(BiFunction<Player, LobbyPlayerAi, PlayerController> factory, int opening, GameRules rules) {
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
        Game game = new Match(rules, seats, "natural turns").createGame();
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
    private static class Recording implements IDecisionRecordCallback {
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

    private static Game contractFixture(BiFunction<Player, LobbyPlayerAi, PlayerController> factory, String context) {
        var rules = new GameRules(context.equals("limited") ? GameType.Draft : GameType.Constructed);
        if (context.equals("variant")) { rules.addAppliedVariant(GameType.Commander); }
        Game game = fixture(factory, 7, rules);
        for (Player player : game.getPlayers()) {
            for (String name : List.of("Forest", "Forest", "Forest", "Grizzly Bears", "Grizzly Bears",
                    "Runeclaw Bear", "Rumbling Baloth")) {
                OriginalCompositeChoice.add(game, player, name);
            }
            if (context.equals("variant")) {
                Card commander = OriginalCompositeChoice.add(game, player, "Isamaru, Hound of Konda");
                commander.setCommander(true);
                game.getAction().moveTo(ZoneType.Command, commander, null, null);
            }
            if (context.equals("unlimited")) {
                player.setMaxHandSize(0);
                player.setUnlimitedHandSize(true);
            }
        }
        game.setAge(context.equals("restart") ? GameStage.RestartedByKarn : GameStage.Play);
        if (List.of("limited", "variant").contains(context)) { game.setAge(GameStage.Mulligan); }
        if (!context.equals("unstarted")) {
            game.getPhaseHandler().devModeSet(PhaseType.MAIN2, game.getPlayers().get(0), false, 6);
        }
        return game;
    }

    private record DiscardChoice(List<Integer> cards, List<Object> facts, long random) { }

    private static List<Object> discardFacts(Game game) {
        List<Object> facts = new ArrayList<>();
        for (Player player : game.getPlayers()) {
            var view = new StateReader(player, 0L);
            try { facts.add(view.snapshot()); }
            finally { view.close(); }
            facts.add(player.getCardsIn(ZoneType.Hand).stream().map(Card::getId).toList());
            facts.add(player.getCardsIn(ZoneType.Library).stream().map(Card::getId).toList());
        }
        return facts;
    }

    private static DiscardChoice chooseDiscard(Game game, int actor, int count) {
        Player player = game.getPlayers().get(actor);
        var hand = List.copyOf(player.getCardsIn(ZoneType.Hand));
        var before = discardFacts(game);
        MyRandom.setRandom(new Random(42));
        var selected = player.getController().chooseCardsToDiscardToMaximumHandSize(count);
        assertEquals(selected.size(), count);
        for (Card card : selected) { assertSame(card, hand.get(hand.indexOf(card))); }
        assertEquals(List.copyOf(player.getCardsIn(ZoneType.Hand)), hand);
        assertEquals(discardFacts(game), before, "Selection must leave discarding to the engine");
        return new DiscardChoice(selected.stream().map(Card::getId).toList(), before, MyRandom.getRandom().nextLong());
    }

    @Test public void cleanupSelectionUsesTheSuppliedCountAndCurrentHand() {
        for (String context : List.of("limited", "variant", "play", "restart", "unlimited")) {
            for (int actor : List.of(0, 1)) {
                for (int count : List.of(1, 2, 7)) {
                    Game baseline = contractFixture((p,l) -> new PlayerControllerAi(p.getGame(), p, l), context);
                    DiscardChoice expected = chooseDiscard(baseline, actor, count);
                    Recording saved = new Recording();
                    Game teacher = contractFixture((p,l) -> new PlayerControllerTeacher(p.getGame(), p, l, saved), context);
                    assertEquals(chooseDiscard(teacher, actor, count), expected);
                    assertNull(saved.pending);
                    assertEquals(saved.requests.size(), 1);
                    var options = (Map<?, ?>) saved.requests.get(0).get("question");
                    assertEquals(options.get("kind"), "discard_cleanup");
                    assertEquals(options.get("min"), count);
                    assertEquals(options.get("max"), count);
                    assertEquals(options.get("optional"), false);
                    assertEquals(((List<?>) options.get("items")).size(), 7);
                    int[] calls = {0};
                    Game replay = contractFixture((p,l) -> new PlayerControllerRl(p.getGame(), p, l, request -> {
                        assertEquals(request, saved.requests.get(0));
                        calls[0]++;
                        return saved.answers.get(0);
                    }), context);
                    DiscardChoice actual = chooseDiscard(replay, actor, count);
                    assertEquals(actual.cards(), expected.cards());
                    assertEquals(actual.facts(), expected.facts());
                    assertEquals(calls[0], 1);
                    close(teacher); close(replay);
                }
            }
        }
    }

    @Test public void cleanupBindingsUseExactCardsAndPreserveAnswerOrder() {
        Game game = contractFixture((p,l) -> new PlayerControllerAi(p.getGame(), p, l), "play");
        Game foreign = contractFixture((p,l) -> new PlayerControllerAi(p.getGame(), p, l), "play");
        Player player = game.getPlayers().get(0);
        var view = new StateReader(player, 0L);
        try {
            var request = CleanupRequests.create(player, 2);
            var hand = player.getCardsIn(ZoneType.Hand);
            assertEquals(request.encodeAnswer(List.of(hand.get(1), hand.get(0))), List.of(1, 0));
            var result = new SelectionExecutor<>(request, List.of(1, 0)).apply();
            assertSame(result.get(0), hand.get(1));
            assertSame(result.get(1), hand.get(0));
            assertThrows(IllegalArgumentException.class, () -> request.encodeAnswer(
                    List.of(CardCopyService.getLKICopy(hand.get(0)), hand.get(1))));
            assertThrows(IllegalArgumentException.class, () -> request.encodeAnswer(
                    List.of(foreign.getPlayers().get(0).getCardsIn(ZoneType.Hand).get(0), hand.get(1))));
            for (List<Integer> invalid : List.of(List.<Integer>of(), List.of(0), List.of(0, 0), List.of(0, 7))) {
                assertThrows(IllegalArgumentException.class, () -> new SelectionExecutor<>(request, invalid).apply());
            }
        } finally { view.close(); }
    }

    @Test public void originalCleanupFailureLeavesAnIncompleteRecord() {
        Game baseline = contractFixture((p,l) -> new PlayerControllerAi(p.getGame(), p, l), "unstarted");
        assertNull(baseline.getPhaseHandler().getPhase());
        assertThrows(NullPointerException.class,
                () -> baseline.getPlayers().get(0).getController().chooseCardsToDiscardToMaximumHandSize(1));
        Recording saved = new Recording();
        Game teacher = contractFixture((p,l) -> new PlayerControllerTeacher(p.getGame(), p, l, saved), "unstarted");
        var controller = teacher.getPlayers().get(0).getController();
        var before = discardFacts(teacher);
        var error = expectThrows(NullPointerException.class, () -> controller.chooseCardsToDiscardToMaximumHandSize(1));
        assertNotNull(saved.pending);
        assertTrue(saved.requests.isEmpty());
        assertTrue(saved.answers.isEmpty());
        assertEquals(discardFacts(teacher), before);
        assertSame(expectThrows(NullPointerException.class,
                () -> controller.chooseCardsToDiscardToMaximumHandSize(1)), error);
        assertFalse(teacher.isGameOver());
        close(teacher);
    }

    @Test public void invalidCleanupCountsStopBeforeAQuestionAndRemainPersistent() {
        for (boolean teacher : List.of(false, true)) {
            for (int count : List.of(-1, 0, 8)) {
                int[] calls = {0};
                Game game = contractFixture((p,l) -> teacher
                        ? new PlayerControllerTeacher(p.getGame(), p, l, new Recording() {
                            @Override public void before(Map<String, Object> request) { calls[0]++; super.before(request); }
                        })
                        : new PlayerControllerRl(p.getGame(), p, l, request -> { calls[0]++; return List.of(0); }), "play");
                var controller = game.getPlayers().get(0).getController();
                var error = expectThrows(IllegalArgumentException.class,
                        () -> controller.chooseCardsToDiscardToMaximumHandSize(count));
                assertSame(expectThrows(IllegalArgumentException.class,
                        () -> controller.chooseCardsToDiscardToMaximumHandSize(1)), error);
                assertEquals(calls[0], 0);
                assertFalse(game.isGameOver());
                close(game);
            }
        }
    }

    @Test public void cleanupRejectsNestedTeacherCallsAndPendingExecution() {
        Game[] nested = {null};
        Recording recording = new Recording() {
            @Override public void before(Map<String, Object> request) {
                super.before(request);
                nested[0].getPlayers().get(0).getController().chooseCardsToDiscardToMaximumHandSize(1);
            }
        };
        nested[0] = contractFixture((p,l) -> new PlayerControllerTeacher(p.getGame(), p, l, recording), "play");
        var controller = nested[0].getPlayers().get(0).getController();
        assertThrows(UnsupportedOperationException.class, () -> controller.chooseCardsToDiscardToMaximumHandSize(1));
        assertNotNull(recording.pending);
        assertTrue(recording.requests.isEmpty());
        close(nested[0]);

        for (boolean teacher : List.of(false, true)) {
            Recording saved = new Recording();
            int[] calls = {0};
            Game game = contractFixture((p,l) -> teacher
                    ? new PlayerControllerTeacher(p.getGame(), p, l, saved)
                    : new PlayerControllerRl(p.getGame(), p, l, request -> { calls[0]++; return List.of(1); }), "play");
            var current = game.getPlayers().get(0).getController();
            assertNotNull(current.chooseSpellAbilityToPlay());
            var error = expectThrows(IllegalStateException.class,
                    () -> current.chooseCardsToDiscardToMaximumHandSize(1));
            assertSame(expectThrows(IllegalStateException.class, current::chooseSpellAbilityToPlay), error);
            assertEquals(teacher ? saved.requests.size() : calls[0], 1);
            close(game);
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
                var options = (Map<?, ?>) request.get("question");
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
            var options = (Map<?, ?>) request.get("question");
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
        test.cleanupSelectionUsesTheSuppliedCountAndCurrentHand();
        test.cleanupBindingsUseExactCardsAndPreserveAnswerOrder();
        test.originalCleanupFailureLeavesAnIncompleteRecord();
        test.invalidCleanupCountsStopBeforeAQuestionAndRemainPersistent();
        test.cleanupRejectsNestedTeacherCallsAndPendingExecution();
        test.nativeTeacherTurnsAndCleanupReplay();
        test.ordinaryClientCanPassIntoCleanupAndInvalidDiscardStops();
        System.out.println("PASS: native land turns, cleanup, teacher state/RNG and replay verified");
    }
}
