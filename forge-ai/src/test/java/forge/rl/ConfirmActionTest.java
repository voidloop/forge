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
        @Override public void before(Map<String, Object> data) {
            assertNull(pending, "Nested confirmation request");
            pending = data;
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
                Game client = fixture((p, l) -> new PlayerControllerRl(p.getGame(), p, l, req -> {
                    Entry entry = recording.entries.get(cursor[0]++);
                    assertEquals(req, entry.request());
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
                    var req = recording.entries.get(recording.entries.size() - 1).request();
                    var data = (Map<?, ?>) req.get("question");
                    assertEquals(data.get("choice"), mode.toString());
                    assertEquals(data.get("prompt"), "");
                    assertEquals(((Map<?, ?>) data.get("ability")).get("api"), "Draw");
                }
            }
            assertEquals(recording.entries.size(), 2 * PlayerActionConfirmMode.values().length);
        } finally { close(game); }
    }

    @Test public void invalidClientAnswersAndStaleBindingStopWithoutEffects() {
        for (List<Integer> answer : List.of(List.<Integer>of(), List.of(-1), List.of(2), List.of(1, 0))) {
            Game game = fixture((p, l) -> new PlayerControllerRl(p.getGame(), p, l, req -> answer), 3);
            try {
                Player actor = game.getPlayers().get(0);
                var before = OriginalCompositeChoice.observe(game);
                var error = expectThrows(IllegalArgumentException.class, () -> AbilityUtils.resolve(ability(actor)));
                assertSame(expectThrows(RuntimeException.class, actor.getController()::awaitNextInput), error);
                assertEquals(OriginalCompositeChoice.observe(game), before);
            } finally { close(game); }
        }
    }
}
