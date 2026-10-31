package forge.rl;

import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerController;
import forge.game.player.PlaySpellAbility;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.Spell;
import forge.game.spellability.SpellAbility;
import forge.deck.Deck;
import forge.util.MyRandom;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.testng.Assert.*;

public class PlayerControllerRlTest {
    @BeforeClass public void initialize() { OriginalCompositeChoice.initialize(Path.of("..")); }

    private OriginalCompositeChoice.Fixture fixture(IPlayerDecisionCallback callback) {
        MyRandom.setRandom(new Random(0));
        var seats = List.of(
                new RegisteredPlayer(new Deck("first")).setPlayer(new LobbyPlayerRl("first", callback)),
                new RegisteredPlayer(new Deck("second")).setPlayer(new LobbyPlayerRl("second", request -> {
                    throw new AssertionError("Unexpected opponent decision: " + request);
                })));
        Game game = new Match(new GameRules(GameType.Constructed), seats, "independent RL").createGame();
        game.getRegisteredPlayers().forEach(player -> player.setLife(20, null));
        game.setAge(GameStage.Play);
        Player player = game.getRegisteredPlayers().get(0);
        Player opponent = game.getRegisteredPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, player, false, 6);
        Card creature = OriginalCompositeChoice.add(game, opponent, "Grizzly Bears");
        SpellAbility target = AbilityUtils.getBasicSpellsFromPlayEffect(creature, opponent).get(0);
        target.setHostCard(game.getAction().moveToStack(creature, target));
        game.getStack().add(target);
        Card source = OriginalCompositeChoice.add(game, player, "Insidious Will");
        Spell offered = (Spell) AbilityUtils.getBasicSpellsFromPlayEffect(source, player).get(0).copyWithNoManaCost();
        return new OriginalCompositeChoice.Fixture(game, player, offered, target);
    }

    @Test public void simpleAndCompositeResponsesUseNativeExecution() {
        var baseline = OriginalCompositeChoice.execute(OriginalCompositeChoice.fixture("baseline", null));
        for (int run = 0; run < 2; run++) {
            AtomicInteger calls = new AtomicInteger();
            var fixture = fixture(request -> {
                calls.incrementAndGet();
                assertEquals(request.keySet(), java.util.Set.of("state", "options"));
                Map<?, ?> options = (Map<?, ?>) request.get("options");
                return switch ((String) options.get("kind")) {
                    case "number" -> List.of(3);
                    case "number_option", "binary", "confirm", "type" -> List.of(1);
                    case "mode_target" -> {
                        assertEquals(((Map<?, ?>) ((List<?>) options.get("targets")).get(0)).get("name"), "Grizzly Bears");
                        assertEquals(((List<?>) options.get("modes")).size(), 2);
                        yield List.of(0, 0);
                    }
                    default -> throw new AssertionError(request);
                };
            });
            PlayerController controller = fixture.player().getController();
            assertSame(controller.getClass().getSuperclass(), PlayerController.class);
            assertEquals(controller.chooseNumber(null, "number", 2, 5), 3);
            assertEquals(controller.chooseNumber(null, "number", List.of(7, 11), null), 11);
            assertTrue(controller.chooseBinary(null, "binary", PlayerController.BinaryChoiceType.HeadsOrTails));
            assertTrue(controller.confirmAction(null, null, "confirm", Map.of()));
            assertEquals(controller.chooseSomeType("type", null, List.of("Elf", "Bear")), "Bear");
            var result = OriginalCompositeChoice.execute(fixture);
            assertEquals(calls.get(), 6, "Composite response must be requested exactly once");
            assertEquals(result.before(), baseline.before());
            assertEquals(result.stack(), baseline.stack());
            assertEquals(result.resolved(), baseline.resolved());
        }
    }

    @Test public void invalidUnsupportedAndDeclinedResponsesStayDistinct() {
        for (List<Integer> invalid : List.of(List.of(99, 0), List.of(0, 99), List.of(0), List.of(-1, 0))) {
            var fixture = fixture(request -> invalid);
            var controller = fixture.player().getController();
            var before = OriginalCompositeChoice.observe(fixture.game());
            assertThrows(IllegalArgumentException.class, () -> controller.playSaFromPlayEffect(fixture.offered()));
            assertEquals(OriginalCompositeChoice.observe(fixture.game()), before);
            assertThrows(IllegalArgumentException.class, () -> controller.chooseNumber(null, "stopped", 0, 1));
        }
        var decline = fixture(request -> List.of());
        var before = OriginalCompositeChoice.observe(decline.game());
        assertFalse(decline.player().getController().playSaFromPlayEffect(decline.offered()));
        assertEquals(OriginalCompositeChoice.observe(decline.game()), before);
        var unsupported = fixture(request -> { throw new AssertionError("Client must not be called"); });
        assertThrows(UnsupportedOperationException.class, () -> unsupported.player().getController().chooseStartingHand(List.of()));
        assertThrows(UnsupportedOperationException.class, () -> unsupported.player().getController().chooseNumber(null, "stopped", 0, 1));
        var missing = fixture(request -> null);
        assertThrows(NullPointerException.class, () -> missing.player().getController().chooseNumber(null, "missing", 0, 1));
        var hidden = fixture(request -> { throw new AssertionError("Hidden source reached client"); });
        hidden.offered().setHostCard(hidden.game().getAction().moveTo(forge.game.zone.ZoneType.Library,
                hidden.offered().getHostCard(), null, null));
        assertFalse(hidden.offered().getHostCard().getView().canBeShownTo(hidden.player().getView()));
        assertThrows(UnsupportedOperationException.class, () -> hidden.player().getController().playSaFromPlayEffect(hidden.offered()));
        assertThrows(UnsupportedOperationException.class, () -> hidden.player().getController().chooseNumber(null, "stopped", 0, 1));
        var rejected = fixture(request -> List.of(0, 0));
        rejected.offered().putParam("ValidAfterStack", "Ability");
        assertThrows(IllegalStateException.class, () -> rejected.player().getController().playSaFromPlayEffect(rejected.offered()));
        assertThrows(IllegalStateException.class, () -> rejected.player().getController().chooseNumber(null, "stopped", 0, 1));
    }

    /** Runs the same checks with only new integration classes and the isolated original engine. */
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toRealPath();
        for (var entry : Map.of(PlayerController.class, "forge-game", PlaySpellAbility.class, "forge-game",
                Game.class, "forge-game", MyRandom.class, "forge-core").entrySet()) {
            Path loaded = Path.of(entry.getKey().getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            assertEquals(loaded, root.resolve(entry.getValue() + "/target/classes").toRealPath());
        }
        OriginalCompositeChoice.initialize(root);
        var test = new PlayerControllerRlTest();
        test.simpleAndCompositeResponsesUseNativeExecution();
        test.invalidUnsupportedAndDeclinedResponsesStayDistinct();
        System.out.println("PASS: independent PlayerControllerRl uses original public APIs; simple/composite responses, native outcome, refusal and errors verified");
    }
}
