package forge.rl;

import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.player.PlayerController;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
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

/** Confirmation return values and their consumption by an unmodified native effect. */
public class ConfirmActionTest {
    private record Entry(Map<String, Object> request, List<Integer> answer) { }
    private static class Recording implements IDecisionRecordCallback {
        final List<Entry> entries = new ArrayList<>();
        Map<String, Object> pending;
        @Override public void before(Map<String, Object> request) {
            assertNull(pending, "Nested confirmation question");
            pending = request;
        }
        @Override public void after(List<Integer> answer) {
            assertNotNull(pending);
            entries.add(new Entry(pending, List.copyOf(answer)));
            pending = null;
        }
    }
    private record Outcome(List<Object> facts, long nextRandom) { }

    @BeforeClass public void initialize() { OriginalCompositeChoice.initialize(Path.of("..")); }

    private static Game fixture(BiFunction<Player, LobbyPlayerAi, PlayerController> controller, int librarySize) {
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
        Game game = new Match(new GameRules(GameType.Constructed), seats, "confirmation contract").createGame();
        game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, game.getPlayers().get(0), false, 6);
        for (Player player : game.getPlayers()) {
            player.setLife(20, null);
            for (int i = 0; i < 2; i++) {
                Card host = OriginalCompositeChoice.add(game, player, "Grizzly Bears");
                player.getZone(ZoneType.Hand).remove(host);
                player.getZone(ZoneType.Battlefield).add(host);
            }
            OriginalCompositeChoice.add(game, player, "Lightning Bolt");
            for (int i = 0; i < librarySize; i++) {
                Card secret = OriginalCompositeChoice.add(game, player, i == 0 ? "Island" : "Black Lotus");
                player.getZone(ZoneType.Hand).remove(secret);
                player.getZone(ZoneType.Library).add(secret);
            }
        }
        return game;
    }

    private static SpellAbility ability(Player player) {
        return ability(player, player.getCardsIn(ZoneType.Battlefield).getFirst());
    }

    private static SpellAbility ability(Player player, Card host) {
        var ability = AbilityFactory.getAbility("DB$ Draw | Defined$ You | NumCards$ 1 | OptionalDecider$ You | SpellDescription$ You may draw a card.", host);
        ability.setActivatingPlayer(player);
        return ability;
    }

    private static boolean confirm(Player player) {
        return player.getController().confirmAction(ability(player), PlayerActionConfirmMode.OptionalChoose, "Draw?", Map.of());
    }

    private static Outcome execute(Game game, boolean accept) {
        for (Player player : game.getPlayers()) {
            int before = player.getCardsIn(ZoneType.Hand).size();
            assertEquals(confirm(player), accept);
            assertEquals(player.getCardsIn(ZoneType.Hand).size(), before, "Confirmation executed the effect");
            AbilityUtils.resolve(ability(player));
            assertEquals(player.getCardsIn(ZoneType.Hand).size(), before + (accept ? 1 : 0));
            assertEquals(player.getCardsIn(ZoneType.Hand).stream().anyMatch(c -> c.getName().equals("Island")), accept);
        }
        return new Outcome(OriginalCompositeChoice.observe(game), MyRandom.getRandom().nextLong());
    }

    private static void close(Game game) {
        for (Player player : game.getPlayers()) {
            if (player.getController() instanceof PlayerControllerTeacher teacher) { teacher.closeObservation(); }
            if (player.getController() instanceof PlayerControllerRl client) { client.closeObservation(); }
        }
    }

    @Test public void nativeYesAndNoPreserveFactsRngAndReplayForBothDeciders() {
        for (boolean accept : List.of(false, true)) {
            int librarySize = accept ? 3 : 1;
            MyRandom.setRandom(new Random(42));
            Outcome baseline = execute(fixture((p, l) -> new PlayerControllerAi(p.getGame(), p, l), librarySize), accept);
            Recording recording = new Recording();
            MyRandom.setRandom(new Random(42));
            Game teacher = fixture((p, l) -> new PlayerControllerTeacher(p.getGame(), p, l, recording), librarySize);
            try {
                assertEquals(execute(teacher, accept), baseline);
                assertNull(recording.pending);
                assertEquals(recording.entries.size(), 4);
                for (Entry entry : recording.entries) { assertEquals(entry.answer(), List.of(accept ? 1 : 0)); }
                String saved = recording.entries.toString();
                MyRandom.setRandom(new Random(42));
                int[] cursor = {0};
                Game client = fixture((p, l) -> new PlayerControllerRl(p.getGame(), p, l, request -> {
                    Entry entry = recording.entries.get(cursor[0]++);
                    assertEquals(request, entry.request());
                    return entry.answer();
                }), librarySize);
                try {
                    assertEquals(execute(client, accept), baseline);
                    assertEquals(cursor[0], recording.entries.size());
                    teacher.getPlayers().get(0).setLife(7, null);
                    assertEquals(recording.entries.toString(), saved);
                } finally { close(client); }
            } finally { close(teacher); }
        }
    }

    @Test public void allNativeModesAndNullMessageAreDetachedBeforeOriginalChoice() {
        Recording recording = new Recording();
        Game game = fixture((p, l) -> new PlayerControllerTeacher(p.getGame(), p, l, recording), 3);
        try {
            for (Player player : game.getPlayers()) {
                for (var mode : PlayerActionConfirmMode.values()) {
                    assertTrue(player.getController().confirmAction(ability(player), mode, null, null));
                    Map<?, ?> options = (Map<?, ?>) recording.entries.get(recording.entries.size() - 1).request().get("question");
                    assertEquals(options.get("choice"), mode.toString());
                    assertEquals(options.get("prompt"), "");
                    assertEquals(((Map<?, ?>) options.get("ability")).get("api"), "Draw");
                }
            }
            assertEquals(recording.entries.size(), 2 * PlayerActionConfirmMode.values().length);
        } finally { close(game); }
    }

    @Test public void rejectsUnsupportedMetadataAndUnboundSourcesBeforeQuestion() {
        for (boolean teacher : List.of(false, true)) {
            for (String invalid : List.of("question", "null_options", "card", "params", "copy", "foreign", "library", "opponent_hand", "face_down", "activator")) {
                Recording recording = new Recording();
                Game game = fixture((p, l) -> teacher ? new PlayerControllerTeacher(p.getGame(), p, l, recording)
                        : new PlayerControllerRl(p.getGame(), p, l, request -> { throw new AssertionError(invalid); }), 3);
                try {
                    Player actor = game.getPlayers().get(0);
                    Card host = actor.getCardsIn(ZoneType.Battlefield).getFirst();
                    SpellAbility sa = ability(actor);
                    switch (invalid) {
                        case "copy" -> sa.setHostCard(new CardCopyService(host).copyCard(false));
                        case "foreign" -> sa.setHostCard(fixture((p, l) -> new PlayerControllerAi(p.getGame(), p, l), 3)
                                .getPlayers().get(0).getCardsIn(ZoneType.Battlefield).getFirst());
                        case "library" -> sa.setHostCard(actor.getCardsIn(ZoneType.Library).getFirst());
                        case "opponent_hand" -> sa.setHostCard(actor.getSingleOpponent().getCardsIn(ZoneType.Hand).getFirst());
                        case "face_down" -> host.setFaceDown(true);
                        case "activator" -> sa.setActivatingPlayer(new Player(actor.getName(), game, actor.getId()));
                        default -> { }
                    }
                    var before = OriginalCompositeChoice.observe(game);
                    var error = expectThrows(UnsupportedOperationException.class, () -> actor.getController().confirmAction(sa, null, "invalid",
                            invalid.equals("null_options") ? null : invalid.equals("question") ? List.of("extra") : List.of(),
                            invalid.equals("card") ? host : null, invalid.equals("params") ? Map.of("secret", host) : null));
                    assertSame(expectThrows(RuntimeException.class, actor.getController()::awaitNextInput), error);
                    assertTrue(recording.entries.isEmpty());
                    assertNull(recording.pending);
                    assertEquals(OriginalCompositeChoice.observe(game), before);
                } finally { close(game); }
            }
        }
    }

    @Test public void invalidClientAnswersAndStaleBindingStopWithoutEffects() {
        for (List<Integer> answer : List.of(List.<Integer>of(), List.of(-1), List.of(2), List.of(1, 0))) {
            Game game = fixture((p, l) -> new PlayerControllerRl(p.getGame(), p, l, request -> answer), 3);
            try {
                Player actor = game.getPlayers().get(0);
                var before = OriginalCompositeChoice.observe(game);
                var error = expectThrows(IllegalArgumentException.class, () -> AbilityUtils.resolve(ability(actor)));
                assertSame(expectThrows(RuntimeException.class, actor.getController()::awaitNextInput), error);
                assertEquals(OriginalCompositeChoice.observe(game), before);
            } finally { close(game); }
        }
        for (boolean teacher : List.of(false, true)) {
            SpellAbility[] bound = {null};
            Recording recording = new Recording() {
                @Override public void before(Map<String, Object> request) {
                    super.before(request);
                    bound[0].setHostCard(bound[0].getActivatingPlayer().getCardsIn(ZoneType.Battlefield).get(1));
                }
            };
            Game game = fixture((p, l) -> teacher ? new PlayerControllerTeacher(p.getGame(), p, l, recording)
                    : new PlayerControllerRl(p.getGame(), p, l, request -> {
                        bound[0].setHostCard(p.getCardsIn(ZoneType.Battlefield).get(1));
                        return List.of(1);
                    }), 3);
            try {
                Player actor = game.getPlayers().get(0);
                bound[0] = ability(actor);
                var error = expectThrows(UnsupportedOperationException.class, () -> actor.getController().confirmAction(bound[0], null, "stale", null));
                assertSame(expectThrows(RuntimeException.class, actor.getController()::awaitNextInput), error);
                assertTrue(recording.entries.isEmpty());
                if (teacher) { assertNotNull(recording.pending); }
            } finally { close(game); }
        }
    }

    @Test public void originalExceptionLeavesAnIncompleteRecordAndOtherControllerHealthy() {
        Recording recording = new Recording();
        Recording healthy = new Recording();
        Game game = fixture((p, l) -> new PlayerControllerTeacher(p.getGame(), p, l,
                p.getName().equals("first") ? recording : healthy), 3);
        try {
            Player actor = game.getPlayers().get(0);
            var before = OriginalCompositeChoice.observe(game);
            var error = expectThrows(IllegalArgumentException.class, () -> actor.getController().confirmAction(null, null, "missing ability", null));
            assertSame(expectThrows(RuntimeException.class, actor.getController()::awaitNextInput), error);
            assertTrue(recording.entries.isEmpty());
            assertNotNull(recording.pending);
            assertTrue(confirm(game.getPlayers().get(1)));
            assertNotNull(recording.pending);
            assertEquals(healthy.entries.size(), 1);
            assertEquals(OriginalCompositeChoice.observe(game), before);
        } finally { close(game); }
    }

    @Test public void callbackExceptionsArePersistentAndLocalToTheirController() {
        for (String stage : List.of("before", "after", "client")) {
            RuntimeException failure = new IllegalStateException("confirmation callback " + stage);
            Recording stopped = new Recording() {
                @Override public void before(Map<String, Object> request) {
                    super.before(request);
                    if (stage.equals("before")) { throw failure; }
                }
                @Override public void after(List<Integer> answer) { throw failure; }
            };
            Recording healthy = new Recording();
            Game game = fixture((p, l) -> {
                if (stage.equals("client")) {
                    return new PlayerControllerRl(p.getGame(), p, l, request -> {
                        if (p.getName().equals("first")) { throw failure; }
                        return List.of(1);
                    });
                }
                return new PlayerControllerTeacher(p.getGame(), p, l, p.getName().equals("first") ? stopped : healthy);
            }, 3);
            try {
                Player actor = game.getPlayers().get(0);
                var before = OriginalCompositeChoice.observe(game);
                assertSame(expectThrows(RuntimeException.class, () -> confirm(actor)), failure);
                assertSame(expectThrows(RuntimeException.class, actor.getController()::awaitNextInput), failure);
                assertTrue(stopped.entries.isEmpty());
                assertTrue(confirm(game.getPlayers().get(1)));
                assertEquals(OriginalCompositeChoice.observe(game), before);
                if (!stage.equals("client")) {
                    assertNotNull(stopped.pending);
                    assertNull(healthy.pending);
                    assertEquals(healthy.entries.size(), 1);
                }
            } finally { close(game); }
        }
    }

    @Test public void pendingExecutionRejectsConfirmationBeforeAnotherQuestion() {
        for (boolean teacher : List.of(false, true)) {
            Recording recording = new Recording();
            int[] calls = {0};
            var setup = BasicLandPriorityTest.fixture(teacher ? new LobbyPlayerTeacher("first", recording)
                    : new LobbyPlayerRl("first", request -> { calls[0]++; return List.of(1); }));
            var controller = setup.player().getController();
            try {
                assertNotNull(controller.chooseSpellAbilityToPlay());
                var error = expectThrows(IllegalStateException.class, () -> controller.confirmAction(null, null, "pending", null));
                assertSame(expectThrows(RuntimeException.class, controller::awaitNextInput), error);
                assertEquals(teacher ? recording.entries.size() : calls[0], 1);
                assertTrue(setup.forest().isInZone(ZoneType.Hand));
            } finally {
                if (teacher) { ((PlayerControllerTeacher) controller).closeObservation(); }
                else { ((PlayerControllerRl) controller).closeObservation(); }
            }
        }
    }

    @Test public void nestedCallbacksCannotCompleteAConfirmationEvenWhenTheyCatchTheError() {
        for (String stage : List.of("observe", "before", "after", "client")) {
            Player[] actor = {null};
            RuntimeException[] nested = {null};
            Runnable reenter = () -> nested[0] = expectThrows(RuntimeException.class, () -> confirm(actor[0]));
            Recording recording = new Recording() {
                @Override public void observe(StateReader reader) { if (stage.equals("observe")) { reenter.run(); } }
                @Override public void before(Map<String, Object> request) {
                    super.before(request);
                    if (stage.equals("before")) { reenter.run(); }
                }
                @Override public void after(List<Integer> answer) {
                    if (stage.equals("after")) { reenter.run(); }
                    else { super.after(answer); }
                }
            };
            int[] calls = {0};
            Game game = fixture((p, l) -> stage.equals("client") ? new PlayerControllerRl(p.getGame(), p, l, request -> {
                calls[0]++;
                reenter.run();
                return List.of(1);
            }) : new PlayerControllerTeacher(p.getGame(), p, l, recording), 3);
            try {
                actor[0] = game.getPlayers().get(0);
                var before = OriginalCompositeChoice.observe(game);
                var error = expectThrows(RuntimeException.class, () -> confirm(actor[0]));
                assertSame(error, nested[0]);
                assertSame(expectThrows(RuntimeException.class, actor[0].getController()::awaitNextInput), error);
                assertTrue(recording.entries.isEmpty());
                assertEquals(calls[0], stage.equals("client") ? 1 : 0);
                if (stage.equals("observe")) { assertNull(recording.pending); }
                assertEquals(OriginalCompositeChoice.observe(game), before);
            } finally { close(game); }
        }
    }

    @Test public void selectionCallbacksCannotOpenAConfirmation() {
        for (boolean teacher : List.of(false, true)) {
            for (String stage : List.of("observe", "answer")) {
                Player[] actor = {null};
                RuntimeException[] nested = {null};
                int[] questions = {0};
                Runnable reenter = () -> nested[0] = expectThrows(RuntimeException.class, () -> confirm(actor[0]));
                Recording recording = new Recording() {
                    @Override public void observe(StateReader reader) { if (stage.equals("observe")) { reenter.run(); } }
                    @Override public void before(Map<String, Object> request) {
                        questions[0]++;
                        super.before(request);
                        if (stage.equals("answer")) { reenter.run(); }
                    }
                };
                IPlayerDecisionCallback callback = new IPlayerDecisionCallback() {
                    @Override public void observe(StateReader reader) { if (stage.equals("observe")) { reenter.run(); } }
                    @Override public List<Integer> choose(Map<String, Object> request) {
                        questions[0]++;
                        reenter.run();
                        return List.of(0);
                    }
                };
                Game game = fixture((p, l) -> teacher ? new PlayerControllerTeacher(p.getGame(), p, l, recording)
                        : new PlayerControllerRl(p.getGame(), p, l, callback), 3);
                try {
                    actor[0] = game.getPlayers().get(0);
                    var sa = AbilityFactory.getAbility("DB$ ChooseCard", actor[0].getCardsIn(ZoneType.Battlefield).getFirst());
                    sa.setActivatingPlayer(actor[0]);
                    var before = OriginalCompositeChoice.observe(game);
                    var error = expectThrows(RuntimeException.class, () -> actor[0].getController().chooseCardsForEffect(
                            actor[0].getCardsIn(ZoneType.Battlefield), sa, "one", 1, 1, false, null));
                    assertSame(error, nested[0]);
                    assertEquals(questions[0], stage.equals("observe") ? 0 : 1);
                    assertTrue(recording.entries.isEmpty());
                    assertSame(expectThrows(RuntimeException.class, actor[0].getController()::awaitNextInput), error);
                    assertEquals(OriginalCompositeChoice.observe(game), before);
                } finally { close(game); }
            }
        }
    }
}
