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
            deck.getMain().add(StaticData.instance().getCommonCards().getCard("Forest"), 24);
            deck.getMain().add(StaticData.instance().getCommonCards().getCard("Grizzly Bears"), 36);
            seats.add(new RegisteredPlayer(deck).setPlayer(lobby));
        }
        Game game = new Match(new GameRules(GameType.Constructed), seats, "London").createGame();
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
    private static final class Recording implements IDecisionRecordCallback {
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
        test.nativeStartupTeacherNeutralityAndFreshReplay();
        test.clientDrawMultipleTucksAndInvalidAnswers();
        System.out.println("PASS: native London startup, teacher state/RNG and replay verified");
    }
}
