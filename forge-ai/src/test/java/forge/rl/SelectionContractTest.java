package forge.rl;

import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCopyService;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.player.PlayerController;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import forge.util.collect.FCollection;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BiFunction;

import static org.testng.Assert.*;

/** Contracts of the three visible selectors, independent of the consuming effect. */
public class SelectionContractTest {
    private record Entry(Map<String, Object> request, List<Integer> answer) { }
    private static class Recording implements IDecisionRecordCallback {
        final List<Entry> entries = new ArrayList<>();
        Map<String, Object> pending;
        @Override public void before(Map<String, Object> data) {
            assertNull(pending, "Nested native calls emitted a second request");
            pending = data;
        }
        @Override public void after(List<Integer> result) {
            assertNotNull(pending);
            entries.add(new Entry(pending, List.copyOf(result)));
            pending = null;
        }
    }
    private record Outcome(List<List<Integer>> answers, List<Object> facts, long nextRandom) { }

    @BeforeClass public void initialize() { OriginalCompositeChoice.initialize(Path.of("..")); }

    private static Game fixture(BiFunction<Player, LobbyPlayerAi, PlayerController> controller) {
        List<RegisteredPlayer> seats = new ArrayList<>();
        for (String name : List.of("first", "second")) {
            var lobby = new LobbyPlayerAi(name, null) {
                @Override public Player createIngamePlayer(Game game, int id) {
                    Player player = new Player(getName(), game, id);
                    player.setFirstController(controller.apply(player, this));
                    return player;
                }
            };
            seats.add(new RegisteredPlayer(new Deck(name)).setPlayer(lobby));
        }
        Game game = new Match(new GameRules(GameType.Constructed), seats, "selection contracts").createGame();
        game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, game.getPlayers().get(0), false, 6);
        for (Player player : game.getPlayers()) {
            player.setLife(20, null);
            for (int i = 0; i < 2; i++) {
                Card bear = OriginalCompositeChoice.add(game, player, "Grizzly Bears");
                player.getZone(ZoneType.Hand).remove(bear);
                player.getZone(ZoneType.Battlefield).add(bear);
            }
            OriginalCompositeChoice.add(game, player, "Lightning Bolt");
            Card secret = OriginalCompositeChoice.add(game, player, "Black Lotus");
            player.getZone(ZoneType.Hand).remove(secret);
            player.getZone(ZoneType.Library).add(secret);
        }
        return game;
    }

    private static SpellAbility ability(Player player) {
        SpellAbility ability = AbilityFactory.getAbility("DB$ ChooseCard", player.getCardsIn(ZoneType.Battlefield).getFirst());
        ability.setActivatingPlayer(player);
        return ability;
    }

    private static <T extends GameEntity> List<Integer> indices(List<T> options, Iterable<T> answer) {
        List<Integer> result = new ArrayList<>();
        for (T chosen : answer) {
            int index = options.indexOf(chosen);
            assertTrue(index >= 0);
            assertSame(chosen, options.get(index));
            result.add(index);
        }
        return List.copyOf(result);
    }

    private static Outcome execute(Game game) {
        List<List<Integer>> answers = new ArrayList<>();
        var initial = OriginalCompositeChoice.observe(game);
        for (Player player : game.getPlayers()) {
            var controller = player.getController();
            var sa = ability(player);
            var cards = new CardCollection(player.getCardsIn(ZoneType.Battlefield));
            var options = new ArrayList<>(cards);
            var players = new FCollection<>(List.of(player.getSingleOpponent(), player));
            var choosePlayer = AbilityFactory.getAbility("DB$ ChoosePlayer", sa.getHostCard());
            choosePlayer.setActivatingPlayer(player);
            Player chosen = controller.chooseSingleEntityForEffect(players, null, choosePlayer, null, false, player.getSingleOpponent(), Map.of());
            answers.add(indices(new ArrayList<>(players), List.of(chosen)));
            Card single = controller.chooseSingleEntityForEffect(new FCollection<>(cards), null, sa, "one", true, player, null);
            answers.add(indices(options, List.of(single)));
            answers.add(indices(options, controller.chooseCardsForEffect(cards, sa, "capped", 3, 4, false, null)));
            answers.add(indices(options, controller.chooseEntitiesForEffect(new FCollection<>(cards), 3, 4, null, sa, "ordered", player, null)));
            var decline = AbilityFactory.getAbility("DB$ ChooseCard | AIMaxAmount$ 0", sa.getHostCard());
            decline.setActivatingPlayer(player);
            answers.add(indices(options, controller.chooseCardsForEffect(cards, decline, "optional pair", 2, 2, true, null)));
            answers.add(indices(options, controller.chooseCardsForEffect(cards, sa, "zero", 0, 0, false, null)));
            assertNull(controller.chooseSingleEntityForEffect(new FCollection<Card>(), null, sa, "empty", false, null, null));
            answers.add(List.of());
            answers.add(indices(options, controller.chooseCardsForEffect(new CardCollection(), sa, "empty cards", 2, 3, false, null)));
        }
        assertEquals(OriginalCompositeChoice.observe(game), initial, "Selectors applied the effect themselves");
        return new Outcome(answers, initial, MyRandom.getRandom().nextLong());
    }

    private static void close(Game game) {
        for (Player player : game.getPlayers()) {
            if (player.getController() instanceof PlayerControllerTeacher teacher) { teacher.closeObservation(); }
            if (player.getController() instanceof PlayerControllerRl client) { client.closeObservation(); }
        }
    }

    @Test public void contractsPreserveFinalAnswersFactsRngAndOuterRecordingForBothPlayers() {
        MyRandom.setRandom(new Random(42));
        Outcome baseline = execute(fixture((p, l) -> new PlayerControllerAi(p.getGame(), p, l)));
        Recording recording = new Recording();
        MyRandom.setRandom(new Random(42));
        Game teacher = fixture((p, l) -> new PlayerControllerTeacher(p.getGame(), p, l, recording));
        try {
            assertEquals(execute(teacher), baseline);
            assertNull(recording.pending);
            assertEquals(recording.entries.size(), 16);
            for (int offset : List.of(0, 8)) {
                var req = (Map<?, ?>) recording.entries.get(offset + 4).request().get("question");
                assertEquals(req.get("min"), 2);
                assertEquals(req.get("max"), 2);
                var items = (List<?>) req.get("items");
                assertEquals(((Map<?, ?>) items.get(0)).get("name"), ((Map<?, ?>) items.get(1)).get("name"));
                assertNotEquals(((Map<?, ?>) items.get(0)).get("key"), ((Map<?, ?>) items.get(1)).get("key"));
                assertEquals(recording.entries.get(offset + 4).answer(), List.of());
            }
            String saved = recording.entries.toString();
            int[] cursor = {0};
            Game client = fixture((p, l) -> new PlayerControllerRl(p.getGame(), p, l, req -> {
                Entry entry = recording.entries.get(cursor[0]++);
                assertEquals(req, entry.request());
                return entry.answer();
            }));
            try {
                MyRandom.setRandom(new Random(42));
                var replay = execute(client);
                assertEquals(replay.answers(), baseline.answers());
                assertEquals(replay.facts(), baseline.facts());
                assertEquals(cursor[0], recording.entries.size());
                teacher.getPlayers().get(0).setLife(7, null);
                assertEquals(recording.entries.toString(), saved);
            } finally { close(client); }
        } finally { close(teacher); }
    }

    @Test public void rejectsNoncurrentObjectsMetadataAndInvalidBoundsBeforeRequest() {
        for (boolean teacher : List.of(false, true)) {
            for (String invalid : List.of("copy", "unbound", "foreign_card", "copied_player", "foreign_player", "related_copy",
                    "library", "opponent_hand", "face_down", "reveal", "params", "negative", "reversed")) {
                Recording recording = new Recording();
                Game game = fixture((p, l) -> teacher
                        ? new PlayerControllerTeacher(p.getGame(), p, l, recording)
                        : new PlayerControllerRl(p.getGame(), p, l, request -> { throw new AssertionError("Invalid selection reached client: " + invalid); }));
                Game foreign = fixture((p, l) -> new PlayerControllerAi(p.getGame(), p, l));
                try {
                    Player actor = game.getPlayers().get(0);
                    var controller = actor.getController();
                    Card card = actor.getCardsIn(ZoneType.Battlefield).getFirst();
                    Player copy = new Player(actor.getName(), game, actor.getId());
                    GameEntity candidate = switch (invalid) {
                        case "copy" -> new CardCopyService(card).copyCard(false);
                        case "unbound" -> new CardCopyService(card).copyCard(true);
                        case "foreign_card" -> foreign.getPlayers().get(0).getCardsIn(ZoneType.Battlefield).getFirst();
                        case "copied_player" -> copy;
                        case "foreign_player" -> foreign.getPlayers().get(0);
                        case "library" -> actor.getCardsIn(ZoneType.Library).getFirst();
                        case "opponent_hand" -> actor.getSingleOpponent().getCardsIn(ZoneType.Hand).getFirst();
                        default -> card;
                    };
                    if (invalid.equals("face_down")) { card.setFaceDown(true); }
                    DelayedReveal reveal = invalid.equals("reveal")
                            ? new DelayedReveal(new CardCollection(card), ZoneType.Battlefield, actor.getView(), "reveal") : null;
                    var facts = OriginalCompositeChoice.observe(game);
                    RuntimeException error = expectThrows(UnsupportedOperationException.class, () -> controller.chooseEntitiesForEffect(
                            new FCollection<>(List.of(candidate)), invalid.equals("negative") ? -1 : 1,
                            invalid.equals("reversed") ? 0 : 1, reveal, ability(actor), "invalid",
                            invalid.equals("related_copy") ? copy : null, invalid.equals("params") ? Map.of("private", true) : null));
                    assertSame(expectThrows(RuntimeException.class, controller::awaitNextInput), error);
                    assertNull(recording.pending);
                    assertTrue(recording.entries.isEmpty());
                    assertEquals(OriginalCompositeChoice.observe(game), facts);
                } finally { close(game); }
            }
        }
    }

    @Test public void selectionsRejectPendingNativeExecutionBeforeAnotherRequest() {
        for (boolean teacher : List.of(false, true)) {
            for (String kind : List.of("single_entity", "cards", "entities")) {
                Recording recording = new Recording();
                int[] requests = {0};
                var fixture = BasicLandPriorityTest.fixture(teacher ? new LobbyPlayerTeacher("first", recording)
                        : new LobbyPlayerRl("first", request -> { requests[0]++; return List.of(1); }));
                try {
                    var controller = fixture.player().getController();
                    assertNotNull(controller.chooseSpellAbilityToPlay());
                    RuntimeException error = expectThrows(IllegalStateException.class, () -> {
                        switch (kind) {
                            case "single_entity" -> controller.chooseSingleEntityForEffect(new FCollection<>(List.of(fixture.forest())), null, "one", false, null);
                            case "cards" -> controller.chooseCardsForEffect(new CardCollection(fixture.forest()), null, "cards", 1, 1, false, null);
                            case "entities" -> controller.chooseEntitiesForEffect(new FCollection<>(List.of(fixture.forest())), 1, 1, null, null, "entities", null, null);
                            default -> throw new AssertionError(kind);
                        }
                    });
                    assertSame(expectThrows(RuntimeException.class, controller::awaitNextInput), error);
                    assertEquals(teacher ? recording.entries.size() : requests[0], 1);
                    assertNull(recording.pending);
                    assertTrue(fixture.forest().isInZone(ZoneType.Hand));
                } finally {
                    if (teacher) { ((PlayerControllerTeacher) fixture.player().getController()).closeObservation(); }
                    else { ((PlayerControllerRl) fixture.player().getController()).closeObservation(); }
                }
            }
        }
    }

    @Test public void nativeZeroMaximumEntitiesBugRemainsAnIncompleteRecord() {
        Game baseline = fixture((p, l) -> new PlayerControllerAi(p.getGame(), p, l));
        Player original = baseline.getPlayers().get(0);
        assertEquals(original.getController().chooseEntitiesForEffect(new FCollection<>(original.getCardsIn(ZoneType.Battlefield)),
                0, 0, null, ability(original), "zero", null, null).size(), 1, "Native do/while behavior changed");
        Recording recording = new Recording();
        Game teacher = fixture((p, l) -> new PlayerControllerTeacher(p.getGame(), p, l, recording));
        try {
            Player actor = teacher.getPlayers().get(0);
            var facts = OriginalCompositeChoice.observe(teacher);
            var error = expectThrows(IllegalArgumentException.class, () -> actor.getController().chooseEntitiesForEffect(
                    new FCollection<>(actor.getCardsIn(ZoneType.Battlefield)), 0, 0, null, ability(actor), "zero", null, null));
            assertNotNull(recording.pending);
            assertTrue(recording.entries.isEmpty());
            assertSame(expectThrows(RuntimeException.class, actor.getController()::awaitNextInput), error);
            assertEquals(OriginalCompositeChoice.observe(teacher), facts);
        } finally { close(teacher); }
    }

    @Test public void selectionCallbackFailuresRemainPersistentAndLocalToTheirController() {
        for (String stage : List.of("before", "after", "client")) {
            RuntimeException failure = new IllegalStateException("selection callback " + stage);
            Recording stopped = new Recording() {
                @Override public void before(Map<String, Object> data) {
                    super.before(data);
                    if (stage.equals("before")) { throw failure; }
                }
                @Override public void after(List<Integer> result) { throw failure; }
            };
            Recording healthy = new Recording();
            Game game = fixture((p, l) -> {
                if (stage.equals("client")) {
                    return new PlayerControllerRl(p.getGame(), p, l, request -> {
                        if (p.getName().equals("first")) { throw failure; }
                        return List.of(1, 0);
                    });
                }
                return new PlayerControllerTeacher(p.getGame(), p, l, p.getName().equals("first") ? stopped : healthy);
            });
            try {
                Player actor = game.getPlayers().get(0);
                var controller = actor.getController();
                var facts = OriginalCompositeChoice.observe(game);
                assertSame(expectThrows(RuntimeException.class, () -> controller.chooseCardsForEffect(
                        actor.getCardsIn(ZoneType.Battlefield), ability(actor), "failed", 2, 2, false, null)), failure);
                assertSame(expectThrows(RuntimeException.class, controller::awaitNextInput), failure);
                assertTrue(stopped.entries.isEmpty());
                if (!stage.equals("client")) { assertNotNull(stopped.pending); }
                Player other = game.getPlayers().get(1);
                var selected = other.getController().chooseCardsForEffect(other.getCardsIn(ZoneType.Battlefield),
                        ability(other), "healthy", 2, 2, false, null);
                assertEquals(selected.size(), 2);
                if (!stage.equals("client")) {
                    assertNull(healthy.pending);
                    assertEquals(healthy.entries.size(), 1);
                }
                assertEquals(OriginalCompositeChoice.observe(game), facts);
            } finally { close(game); }
        }
    }
}
