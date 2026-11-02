package forge.rl;

import com.google.common.eventbus.Subscribe;
import forge.game.event.GameEventPlayerPriority;
import forge.game.event.GameEventGameStarted;
import forge.StaticData;
import forge.MulliganDefs;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.*;
import forge.game.phase.PhaseType;
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

public class MulliganTest {
    @BeforeClass public void initialize() {
        OriginalCompositeChoice.initialize(Path.of(".."));
        StaticData.instance().setFilteredHandsEnabled(false);
        StaticData.instance().setMulliganRule(MulliganDefs.MulliganRule.London);
    }
    private static final class AtPriority extends RuntimeException { }

    private static Game fixture(BiFunction<Player, LobbyPlayerAi, PlayerController> factory, boolean badHand) {
        return fixture(factory, badHand, new GameRules(GameType.Constructed), 2);
    }

    private static Game fixture(BiFunction<Player, LobbyPlayerAi, PlayerController> factory, boolean badHand,
            GameRules rules, int count) {
        List<RegisteredPlayer> seats = new ArrayList<>();
        for (String name : List.of("first", "second", "third").subList(0, count)) {
            var lobby = new LobbyPlayerAi(name, null) {
                @Override public Player createIngamePlayer(Game game, int id) {
                    Player p = new Player(getName(), game, id);
                    p.setFirstController(factory.apply(p, this));
                    return p;
                }
            };
            Deck deck = new Deck(name);
            deck.getMain().add(StaticData.instance().getCommonCards().getCard("Forest"), 24);
            deck.getMain().add(StaticData.instance().getCommonCards().getCard("Grizzly Bears"), 36);
            seats.add(new RegisteredPlayer(deck).setPlayer(lobby));
        }
        Game game = new Match(rules, seats, "London").createGame();
        for (Player p : game.getPlayers()) {
            p.setLife(20, null);
            for (int i = 0; i < 60; i++) {
                // A playable seven or a deliberately landless opening, then a mixed library.
                String name = i < 7 ? (badHand || i >= 3 ? "Grizzly Bears" : "Forest")
                        : (i % 2 == 0 ? "Forest" : "Grizzly Bears");
                p.getZone(ZoneType.Library).add(CardFactory.getCard(StaticData.instance().getCommonCards().getCard(name), p, game));
            }
        }
        return game;
    }

    public static final class FirstPriority {
        final Game game;
        int selected = -1;
        boolean reached;
        FirstPriority(Game game) { this.game = game; }
        @Subscribe public void started(GameEventGameStarted event) { selected = event.firstTurn().getId(); }
        @Subscribe public void priority(GameEventPlayerPriority event) {
            reached = true;
            Player p = game.getPhaseHandler().getPriorityPlayer();
            p.dangerouslySetController(new PlayerControllerAi(game, p, new LobbyPlayerAi("stop", null)) {
                @Override public List<SpellAbility> chooseSpellAbilityToPlay() { throw new AtPriority(); }
            });
        }
    }
    private record Outcome(List<Object> facts, long random) { }
    private static Outcome start(Game game, long seed) {
        MyRandom.setRandom(new Random(seed));
        FirstPriority boundary = new FirstPriority(game);
        game.subscribeToEvents(boundary);
        List<PlayerController> controllers = game.getPlayers().stream().map(Player::getController).toList();
        try { assertThrows(AtPriority.class, () -> game.getAction().startGame(null)); }
        finally {
            for (int i = 0; i < controllers.size(); i++) { game.getPlayers().get(i).dangerouslySetController(controllers.get(i)); }
        }
        assertTrue(boundary.reached);
        assertEquals(game.getStartingPlayer().getId(), boundary.selected);
        assertEquals(game.getAge(), GameStage.Play);
        assertSame(game.getStartingPlayer(), game.getPhaseHandler().getPlayerTurn());
        assertEquals(game.getPhaseHandler().getTurn(), 1);
        assertFalse(game.isGameOver());
        return new Outcome(OriginalCompositeChoice.observe(game), MyRandom.getRandom().nextLong());
    }
    private static boolean priority(Map<String, Object> request) {
        return ((String) ((Map<?, ?>) request.get("options")).get("kind")).startsWith("priority_");
    }
    private static class Recording implements IDecisionRecordCallback {
        final List<Map<String, Object>> requests = new ArrayList<>();
        final List<List<Integer>> answers = new ArrayList<>();
        Map<String, Object> pending;
        @Override public void before(Map<String, Object> data) {
            if (priority(data)) { throw new AtPriority(); }
            assertNull(pending); pending = data;
        }
        @Override public void after(List<Integer> answer) {
            assertNotNull(pending); requests.add(pending); answers.add(List.copyOf(answer)); pending = null;
        }
    }
    private static void close(Game game) {
        for (Player p : game.getPlayers()) {
            if (p.getController() instanceof PlayerControllerRl rl) { rl.closeObservation(); }
            if (p.getController() instanceof PlayerControllerTeacher t) { t.closeObservation(); }
        }
    }

    private static Game contractFixture(BiFunction<Player, LobbyPlayerAi, PlayerController> factory, String context) {
        var rules = new GameRules(context.equals("limited") ? GameType.Draft : GameType.Constructed);
        if (context.equals("variant")) { rules.addAppliedVariant(GameType.Commander); }
        Game game = fixture(factory, false, rules, 2);
        game.setAge(GameStage.Mulligan);
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
        }
        if (context.equals("play") || context.equals("restart")) {
            game.setAge(context.equals("play") ? GameStage.Play : GameStage.RestartedByKarn);
            game.getPhaseHandler().devModeSet(PhaseType.MAIN2, game.getPlayers().get(0), false, 6);
        }
        return game;
    }

    private static List<Object> facts(Game game) {
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

    private record Choices(boolean keep, List<Integer> tucked, List<Integer> offeredAfter, List<Object> facts, long random) { }

    private static Choices choose(Game game, int actor, int first, int count) {
        Player player = game.getPlayers().get(actor);
        var controller = player.getController();
        var hand = new CardCollection(player.getCardsIn(ZoneType.Hand));
        var offered = List.copyOf(hand);
        var before = facts(game);
        MyRandom.setRandom(new Random(42));
        boolean keep = controller.mulliganKeepHand(game.getPlayers().get(first), count);
        assertEquals(facts(game), before);
        var selected = controller.tuckCardsViaMulligan(hand, count);
        assertEquals(selected.size(), count);
        for (Card card : selected) { assertSame(card, offered.get(offered.indexOf(card))); }
        assertEquals(facts(game), before, "Selection must leave native movement to London");
        for (Card card : selected) { game.getAction().moveToLibrary(card, -1, null); }
        var tucked = selected.stream().map(Card::getId).toList();
        var library = player.getCardsIn(ZoneType.Library);
        assertEquals(library.subList(library.size() - count, library.size()).stream().map(Card::getId).toList(), tucked);
        return new Choices(keep, tucked, hand.stream().map(Card::getId).toList(), facts(game), MyRandom.getRandom().nextLong());
    }

    @Test public void londonContractsUseNativeInputsRatherThanFixtureContext() {
        for (String context : List.of("limited", "variant", "play", "restart")) {
            for (int actor : List.of(0, 1)) {
                for (int first : List.of(0, 1)) {
                    for (int count : List.of(0, 2, 7)) {
                        Game baseline = contractFixture((p,l) -> new PlayerControllerAi(p.getGame(), p, l), context);
                        Choices expected = choose(baseline, actor, first, count);
                        Recording saved = new Recording();
                        Game teacher = contractFixture((p,l) -> new PlayerControllerTeacher(p.getGame(), p, l, saved), context);
                        assertEquals(choose(teacher, actor, first, count), expected);
                        assertNull(saved.pending);
                        assertEquals(saved.requests.size(), count == 0 ? 1 : 2);
                        var keep = (Map<?, ?>) saved.requests.get(0).get("options");
                        assertEquals(keep.get("firstPlayer"), Map.of("kind", "player", "id", teacher.getPlayers().get(first).getId()));
                        assertEquals(keep.get("cardsToReturn"), count);
                        assertEquals(saved.answers.get(0), List.of(expected.keep() ? 1 : 0));
                        if (count != 0) {
                            var tuck = (Map<?, ?>) saved.requests.get(1).get("options");
                            assertEquals(((List<?>) tuck.get("items")).size(), 7, "Bindings must precede AI list mutation");
                            assertEquals(tuck.get("min"), count);
                            assertEquals(tuck.get("max"), count);
                            assertTrue(expected.offeredAfter().size() < 7);
                        }
                        int[] index = {0};
                        Game replay = contractFixture((p,l) -> new PlayerControllerRl(p.getGame(), p, l, request -> {
                            assertEquals(request, saved.requests.get(index[0]));
                            return saved.answers.get(index[0]++);
                        }), context);
                        Choices actual = choose(replay, actor, first, count);
                        assertEquals(actual.keep(), expected.keep());
                        assertEquals(actual.tucked(), expected.tucked());
                        assertEquals(actual.facts(), expected.facts());
                        assertEquals(actual.random(), expected.random());
                        assertEquals(index[0], saved.requests.size());
                        close(teacher); close(replay);
                    }
                }
            }
        }
    }

    @Test public void invalidNativeBindingsAndCountsStopBeforeAQuestion() {
        for (boolean teacher : List.of(false, true)) {
            for (String invalid : List.of("negative_keep", "foreign_first", "negative_tuck", "too_many",
                    "partial", "foreign_hand", "opponent_hand", "copied_card")) {
                int[] calls = {0};
                Game game = contractFixture((p,l) -> teacher
                        ? new PlayerControllerTeacher(p.getGame(), p, l, new Recording() {
                            @Override public void before(Map<String, Object> data) { calls[0]++; super.before(data); }
                        })
                        : new PlayerControllerRl(p.getGame(), p, l, request -> { calls[0]++; return List.of(1); }), "limited");
                Game foreign = contractFixture((p,l) -> new PlayerControllerAi(p.getGame(), p, l), "limited");
                Player player = game.getPlayers().get(0);
                var controller = player.getController();
                var hand = new CardCollection(player.getCardsIn(ZoneType.Hand));
                var before = facts(game);
                Runnable attempt = switch (invalid) {
                    case "negative_keep" -> () -> controller.mulliganKeepHand(player, -1);
                    case "foreign_first" -> () -> controller.mulliganKeepHand(foreign.getPlayers().get(0), 0);
                    case "negative_tuck" -> () -> controller.tuckCardsViaMulligan(hand, -1);
                    case "too_many" -> () -> controller.tuckCardsViaMulligan(hand, 8);
                    case "partial" -> () -> controller.tuckCardsViaMulligan(new CardCollection(hand.subList(0, 6)), 2);
                    case "foreign_hand" -> () -> controller.tuckCardsViaMulligan(foreign.getPlayers().get(0).getCardsIn(ZoneType.Hand), 2);
                    case "opponent_hand" -> () -> controller.tuckCardsViaMulligan(player.getSingleOpponent().getCardsIn(ZoneType.Hand), 0);
                    case "copied_card" -> () -> controller.tuckCardsViaMulligan(new CardCollection(hand.stream()
                            .map(card -> card == hand.get(0) ? CardCopyService.getLKICopy(card) : card).toList()), 2);
                    default -> throw new AssertionError(invalid);
                };
                assertThrows(RuntimeException.class, attempt::run);
                assertThrows(RuntimeException.class, controller::awaitNextInput);
                assertEquals(calls[0], 0);
                assertEquals(facts(game), before);
                assertFalse(game.isGameOver());
                assertTrue(player.getSingleOpponent().getController().mulliganKeepHand(player, 0),
                        "Failure must remain local to the deciding controller");
                close(game);
            }
        }
    }

    @Test public void keepCountIsNotCappedToTheRemainingHandAndEmptyTuckIsSilent() {
        Recording saved = new Recording();
        Game teacher = fixture((p,l) -> new PlayerControllerTeacher(p.getGame(), p, l, saved), false);
        Player player = teacher.getPlayers().get(0);
        assertTrue(player.getController().mulliganKeepHand(player, 7));
        assertTrue(player.getController().tuckCardsViaMulligan(player.getCardsIn(ZoneType.Hand), 0).isEmpty());
        assertEquals(saved.requests.size(), 1);
        assertEquals(((Map<?, ?>) saved.requests.get(0).get("options")).get("cardsToReturn"), 7);
        int[] calls = {0};
        Game replay = fixture((p,l) -> new PlayerControllerRl(p.getGame(), p, l, request -> {
            assertEquals(request, saved.requests.get(calls[0]));
            return saved.answers.get(calls[0]++);
        }), false);
        Player fresh = replay.getPlayers().get(0);
        assertTrue(fresh.getController().mulliganKeepHand(fresh, 7));
        assertTrue(fresh.getController().tuckCardsViaMulligan(fresh.getCardsIn(ZoneType.Hand), 0).isEmpty());
        assertEquals(calls[0], 1);
        close(teacher); close(replay);
    }

    @Test public void londonWorkflowLimitsRemainExplicit() {
        for (String limit : List.of("one_player", "multiplayer", "filtered", "paris")) {
            for (boolean teacher : List.of(false, true)) {
                for (boolean tuck : List.of(false, true)) {
                    int count = 2;
                    if (limit.equals("one_player")) { count = 1; }
                    if (limit.equals("multiplayer")) { count = 3; }
                    Game game = fixture((p,l) -> teacher
                            ? new PlayerControllerTeacher(p.getGame(), p, l, new Recording() {
                                @Override public void before(Map<String, Object> data) { fail("Unsupported London reached recorder"); }
                            })
                            : new PlayerControllerRl(p.getGame(), p, l, request -> { throw new AssertionError("Unsupported London reached client"); }),
                            false, new GameRules(GameType.Constructed), count);
                    try {
                        StaticData.instance().setFilteredHandsEnabled(limit.equals("filtered"));
                        StaticData.instance().setMulliganRule(limit.equals("paris") ? MulliganDefs.MulliganRule.Paris : MulliganDefs.MulliganRule.London);
                        var controller = game.getPlayers().get(0).getController();
                        assertThrows(UnsupportedOperationException.class, () -> {
                            if (tuck) { controller.tuckCardsViaMulligan(new CardCollection(), 0); }
                            else { controller.mulliganKeepHand(game.getPlayers().get(0), 0); }
                        });
                        assertThrows(UnsupportedOperationException.class, controller::awaitNextInput);
                    } finally {
                        StaticData.instance().setFilteredHandsEnabled(false);
                        StaticData.instance().setMulliganRule(MulliganDefs.MulliganRule.London);
                        close(game);
                    }
                }
            }
        }
    }

    @Test public void nestedTeacherLondonAndUnfinishedExecutionDoNotOpenAnotherQuestion() {
        for (boolean tuck : List.of(false, true)) {
            Game[] nested = {null};
            Recording saved = new Recording() {
                @Override public void before(Map<String, Object> data) {
                    super.before(data);
                    Player p = nested[0].getPlayers().get(0);
                    assertThrows(IllegalStateException.class, () -> {
                        if (tuck) { p.getController().tuckCardsViaMulligan(p.getCardsIn(ZoneType.Hand), 0); }
                        else { p.getController().mulliganKeepHand(p, 0); }
                    });
                }
            };
            nested[0] = contractFixture((p,l) -> new PlayerControllerTeacher(p.getGame(), p, l, saved), "limited");
            var controller = nested[0].getPlayers().get(0).getController();
            assertThrows(IllegalStateException.class, () -> controller.mulliganKeepHand(nested[0].getPlayers().get(0), 0));
            assertThrows(IllegalStateException.class, controller::awaitNextInput);
            assertNotNull(saved.pending);
            assertTrue(saved.answers.isEmpty());
            close(nested[0]);

            for (boolean teacher : List.of(false, true)) {
                Recording pending = new Recording() {
                    @Override public void before(Map<String, Object> data) { assertNull(this.pending); this.pending = data; }
                };
                int[] clientCalls = {0};
                Game game = fixture((p,l) -> teacher
                        ? new PlayerControllerTeacher(p.getGame(), p, l, pending)
                        : new PlayerControllerRl(p.getGame(), p, l, request -> { clientCalls[0]++; return List.of(1); }), false);
                Player p = game.getPlayers().get(0);
                game.setAge(GameStage.Play);
                game.getPhaseHandler().devModeSet(PhaseType.MAIN2, p, false, 6);
                game.getPhaseHandler().setPriority(p);
                OriginalCompositeChoice.add(game, p, "Forest");
                assertNotNull(p.getController().chooseSpellAbilityToPlay());
                assertThrows(IllegalStateException.class, () -> {
                    if (tuck) { p.getController().tuckCardsViaMulligan(p.getCardsIn(ZoneType.Hand), 0); }
                    else { p.getController().mulliganKeepHand(p, 0); }
                });
                assertThrows(IllegalStateException.class, p.getController()::awaitNextInput);
                assertEquals(teacher ? pending.requests.size() + (pending.pending == null ? 0 : 1) : clientCalls[0], 1);
                close(game);
            }
        }
    }

    @Test public void nativeStartupTeacherNeutralityAndFreshReplay() {
        for (boolean bad : List.of(false, true)) {
            Recording saved = new Recording();
            Game baseline = fixture((p,l) -> new PlayerControllerAi(p.getGame(), p, l) {
                @Override public List<SpellAbility> chooseSpellAbilityToPlay() { throw new AtPriority(); }
            }, bad);
            Outcome expected = start(baseline, 42);
            Game teacher = fixture((p,l) -> new PlayerControllerTeacher(p.getGame(), p, l, saved), bad);
            assertEquals(start(teacher, 42), expected);
            assertNull(saved.pending);
            assertTrue(saved.requests.stream().anyMatch(r -> ((Map<?, ?>) r.get("options")).get("kind").equals("mulligan_keep")));
            assertEquals(saved.requests.stream().anyMatch(r -> ((Map<?, ?>) r.get("options")).get("kind").equals("mulligan_tuck")), bad);
            int[] index = {0};
            Game replay = fixture((p,l) -> new PlayerControllerRl(p.getGame(), p, l, request -> {
                if (priority(request)) { throw new AtPriority(); }
                assertEquals(request, saved.requests.get(index[0]));
                return saved.answers.get(index[0]++);
            }), bad);
            assertNotSame(teacher.getPlayers().get(0), replay.getPlayers().get(0));
            assertEquals(start(replay, 42), expected);
            assertEquals(index[0], saved.requests.size());
            close(teacher); close(replay);
        }
    }

    @Test public void clientDrawMultipleTucksAndInvalidAnswers() {
        int[] keeps = {0};
        List<Card> ordered = new ArrayList<>();
        Game game = fixture((p,l) -> new PlayerControllerRl(p.getGame(), p, l, request -> {
            var options = (Map<?, ?>) request.get("options");
            switch ((String) options.get("kind")) {
                case "starting_player": return List.of(p.getGame().getPlayers().indexOf(p.getSingleOpponent()));
                case "mulligan_keep": return List.of(keeps[0]++ < 4 ? 0 : 1);
                case "mulligan_tuck":
                    int count = (Integer) options.get("min");
                    var hand = p.getCardsIn(ZoneType.Hand);
                    ordered.clear();
                    List<Integer> answer = new ArrayList<>();
                    for (int i = count - 1; i >= 0; i--) { answer.add(i); ordered.add(hand.get(i)); }
                    return answer;
                default: throw new AtPriority();
            }
        }), false);
        start(game, 42);
        assertTrue(ordered.size() >= 2);
        Player owner = ordered.get(0).getOwner();
        var library = owner.getCardsIn(ZoneType.Library);
        assertEquals(library.subList(library.size() - ordered.size(), library.size()).stream().map(Card::getId).toList(),
                ordered.stream().map(Card::getId).toList());
        close(game);

        for (List<Integer> invalid : List.of(List.<Integer>of(), List.of(2), List.of(0,1))) {
            Game failed = fixture((p,l) -> new PlayerControllerRl(p.getGame(), p, l, r -> invalid), false);
            failed.setAge(GameStage.Mulligan);
            Player p = failed.getPlayers().get(0);
            assertThrows(IllegalArgumentException.class, () -> p.getController().mulliganKeepHand(p, 0));
            assertThrows(IllegalArgumentException.class, () -> p.getController().mulliganKeepHand(p, 0));
            assertFalse(failed.isGameOver()); close(failed);
        }
    }

    public static void main(String[] args) {
        var test = new MulliganTest();
        OriginalCompositeChoice.initialize(Path.of(args[0]));
        StaticData.instance().setFilteredHandsEnabled(false);
        StaticData.instance().setMulliganRule(MulliganDefs.MulliganRule.London);
        test.londonContractsUseNativeInputsRatherThanFixtureContext();
        test.invalidNativeBindingsAndCountsStopBeforeAQuestion();
        test.keepCountIsNotCappedToTheRemainingHandAndEmptyTuckIsSilent();
        test.londonWorkflowLimitsRemainExplicit();
        test.nestedTeacherLondonAndUnfinishedExecutionDoNotOpenAnotherQuestion();
        test.nativeStartupTeacherNeutralityAndFreshReplay();
        test.clientDrawMultipleTucksAndInvalidAnswers();
        System.out.println("PASS: native London startup, teacher state/RNG and replay verified");
    }
}
