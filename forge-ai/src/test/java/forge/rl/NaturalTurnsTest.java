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
import forge.rl.decisions.CleanupRequests;
import forge.rl.decisions.SelectionExecutor;
import forge.rl.decisions.SelectionRequest;
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
        @Override public void before(Map<String, Object> data) { assertNull(pending); pending = data; }
        @Override public void after(List<Integer> result) {
            requests.add(pending); answers.add(List.copyOf(result)); pending = null;
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
            try (var view = new StateReader(player, 0L)) {
                facts.add(view.snapshot());
            }
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
                    var data = (Map<?, ?>) saved.requests.get(0).get("question");
                    assertEquals(data.get("kind"), "discard_cleanup");
                    assertEquals(data.get("min"), count);
                    assertEquals(data.get("max"), count);
                    assertEquals(data.get("optional"), false);
                    assertEquals(((List<?>) data.get("items")).size(), 7);
                    int[] calls = {0};
                    Game replay = contractFixture((p,l) -> new PlayerControllerRl(p.getGame(), p, l, req -> {
                        assertEquals(req, saved.requests.get(0));
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
        try {
            SelectionRequest<Card> request = CleanupRequests.create(player, 2);
            var hand = player.getCardsIn(ZoneType.Hand);
            assertEquals(request.encode(List.of(hand.get(1), hand.get(0))), List.of(1, 0));
            var result = new SelectionExecutor<>(request, List.of(1, 0)).apply();
            assertSame(result.get(0), hand.get(1));
            assertSame(result.get(1), hand.get(0));
            assertThrows(IllegalArgumentException.class, () -> request.encode(
                    List.of(CardCopyService.getLKICopy(hand.get(0)), hand.get(1))));
            assertThrows(IllegalArgumentException.class, () -> request.encode(
                    List.of(foreign.getPlayers().get(0).getCardsIn(ZoneType.Hand).get(0), hand.get(1))));
            for (List<Integer> invalid : List.of(List.<Integer>of(), List.of(0), List.of(0, 0), List.of(0, 7))) {
                assertThrows(IllegalArgumentException.class, () -> new SelectionExecutor<>(request, invalid).apply());
            }
        } finally { close(game); close(foreign); }
    }

    @Test public void ordinaryClientCanPassIntoCleanupAndInvalidDiscardStops() {
        int[] discards = {0};
        var game = fixture((p,l) -> new PlayerControllerRl(p.getGame(), p, l, req -> {
            var data = (Map<?, ?>) req.get("question");
            switch ((String) data.get("kind")) {
                case "starting_player": return List.of(0);
                case "mulligan_keep": return List.of(1);
                case "priority":
                    return List.of(p.getCardsIn(ZoneType.Battlefield).isEmpty()
                            && ((List<?>) data.get("items")).size() > 1 ? 1 : 0);
                case "discard_cleanup":
                    discards[0]++;
                    int count = (Integer) data.get("min");
                    return java.util.stream.IntStream.range(0, count).boxed().toList();
                default: throw new AssertionError(data);
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
    }
}
