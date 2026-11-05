package forge.rl;

import forge.LobbyPlayer;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.card.ColorSet;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Random;

import static org.testng.Assert.*;

/** A real mana activation; the preserving enchantment makes floating mana useful to the native AI. */
public class ManaActivationTest {
    record Fixture(Game game, Player player, Card land) { }

    @BeforeClass public void initialize() { OriginalCompositeChoice.initialize(Path.of("..")); }

    static Fixture fixture(LobbyPlayer lobby) {
        var seats = List.of(
                new RegisteredPlayer(new Deck("first")).setPlayer(lobby),
                new RegisteredPlayer(new Deck("second")).setPlayer(new LobbyPlayerRl("second", request -> {
                    throw new AssertionError("Unexpected opponent decision");
                })));
        Game game = new Match(new GameRules(GameType.Constructed), seats, "single mana activation").createGame();
        game.getRegisteredPlayers().forEach(player -> player.setLife(20, null));
        game.setAge(GameStage.Play);
        Player player = game.getRegisteredPlayers().get(0);
        Player opponent = game.getRegisteredPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, opponent, false, 1);
        game.getPhaseHandler().setPriority(player);
        Card land = OriginalCompositeChoice.add(game, player, "Simic Guildgate");
        player.getZone(ZoneType.Hand).remove(land);
        player.getZone(ZoneType.Battlefield).add(land);
        Card preserving = OriginalCompositeChoice.add(game, player, "Upwelling");
        player.getZone(ZoneType.Hand).remove(preserving);
        player.getZone(ZoneType.Battlefield).add(preserving);
        return new Fixture(game, player, land);
    }

    @Test public void recorderPreparesManaRequestWithItsObservedReader() {
        Fixture fixture = fixture(new LobbyPlayerAi("first", null));
        StateReader[] observed = {null};
        Object[] landKey = {null};
        int[] calls = {0};
        int[] answers = {0};
        int[] bindings = {0};
        var recorder = new forge.rl.decisions.GameRecorder(fixture.player(), new IDecisionRecordCallback() {
            @Override public void observe(StateReader reader) {
                assertNull(observed[0]);
                observed[0] = reader;
                // Assign this reference before the snapshot to distinguish the session from a fresh reader.
                reader.reference(fixture.player().getCardsIn(ZoneType.Battlefield).stream()
                        .filter(card -> card != fixture.land()).findFirst().orElseThrow());
                landKey[0] = reader.reference(fixture.land());
            }
            @Override public void before(Map<String, Object> request) {
                assertEquals(request.get("state"), observed[0].snapshot());
                var question = (Map<?, ?>) request.get("question");
                var items = (List<?>) question.get("items");
                assertEquals(((Map<?, ?>) items.get(1)).get("key"), landKey[0]);
            }
            @Override public void after(List<Integer> answer) {
                assertEquals(answer, List.of(0));
                assertEquals(bindings[0], calls[0], "Binding must precede the answer callback");
                answers[0]++;
            }
        });
        try {
            for (int i = 0; i < 2; i++) {
                List<Integer> answer = recorder.record(new forge.rl.decisions.ManaDecision(), request -> {
                    calls[0]++;
                    var answerToRecord = List.of(0);
                    assertEquals(calls[0], bindings[0] + 1, "Binding must follow the original call");
                    assertSame(request.selected().ability.getHostCard(), fixture.land());
                    bindings[0]++;
                    return answerToRecord;
                });
                assertEquals(answer, List.of(0));
            }
            assertEquals(calls[0], 2);
            assertEquals(bindings[0], 2);
            assertEquals(answers[0], 2);
            assertEquals(observed[0].reference(fixture.land()), landKey[0]);
        } finally {
            recorder.close();
        }
    }

    @Test public void nativeManaActivationAsksForColorAfterTapWithoutNewPriority() {
        int[] colorCalls = {0};
        byte[] chosenColor = {0};
        Fixture fixture = fixture(new LobbyPlayerAi("first", null) {
            @Override public Player createIngamePlayer(Game game, int id) {
                Player player = new Player(getName(), game, id);
                player.setFirstController(new PlayerControllerAi(game, player, this) {
                    @Override public byte chooseColor(String message, SpellAbility ability, ColorSet colors) {
                        colorCalls[0]++;
                        assertTrue(ability.getHostCard().isTapped());
                        assertSame(game.getPhaseHandler().getPriorityPlayer(), player);
                        assertTrue(game.getStack().isEmpty());
                        assertEquals(player.getManaPool().totalMana(), 0);
                        assertEquals(colors.countColors(), 2);
                        chosenColor[0] = super.chooseColor(message, ability, colors);
                        return chosenColor[0];
                    }
                });
                return player;
            }
        });
        var controller = fixture.player().getController();
        var chosen = controller.chooseSpellAbilityToPlay();
        assertNotNull(chosen, "Native AI must choose a real activation without a supplied teacher answer");
        assertEquals(chosen.size(), 1);
        SpellAbility ability = chosen.get(0);
        assertTrue(ability.isManaAbility());
        assertSame(ability.getHostCard(), fixture.land());
        assertFalse(fixture.land().isTapped());
        assertEquals(ability.getManaPart().getExpressChoice(), "");
        assertEquals(colorCalls[0], 0);
        assertTrue(controller.playChosenSpellAbility(ability));
        assertEquals(colorCalls[0], 1);
        assertTrue(fixture.land().isTapped());
        assertEquals(fixture.player().getManaPool().totalMana(), 1);
        assertEquals(fixture.player().getManaPool().getAmountOfColor(chosenColor[0]), 1);
        assertSame(fixture.game().getPhaseHandler().getPriorityPlayer(), fixture.player());
        assertTrue(fixture.game().getStack().isEmpty());
        System.out.println("PASS: native Simic Guildgate activation pays tap, asks for color, produces one mana without a new priority");
    }

    private record Entry(Map<String, Object> request, List<Integer> answer) { }
    private static final class Recording implements IDecisionRecordCallback {
        final List<Entry> entries = new ArrayList<>();
        Map<String, Object> pending;
        @Override public void before(Map<String, Object> request) { assertNull(pending); pending = request; }
        @Override public void after(List<Integer> answer) {
            assertNotNull(pending);
            entries.add(new Entry(pending, List.copyOf(answer)));
            pending = null;
        }
    }
    private record Outcome(List<List<Object>> states, long randomAfter) { }

    private static Outcome execute(Fixture fixture) {
        List<List<Object>> states = new ArrayList<>();
        var controller = fixture.player().getController();
        states.add(OriginalCompositeChoice.observe(fixture.game()));
        var chosen = controller.chooseSpellAbilityToPlay();
        assertNotNull(chosen);
        assertFalse(fixture.land().isTapped());
        assertEquals(fixture.player().getManaPool().totalMana(), 0);
        assertTrue(controller.playChosenSpellAbility(chosen.get(0)));
        assertTrue(fixture.land().isTapped());
        assertEquals(fixture.player().getManaPool().totalMana(), 1);
        states.add(OriginalCompositeChoice.observe(fixture.game()));
        assertNull(controller.chooseSpellAbilityToPlay());
        return new Outcome(states, MyRandom.getRandom().nextLong());
    }

    @Test public void teacherIsNeutralAndReplaysTheSubordinateColorWithoutAi() {
        MyRandom.setRandom(new Random(0));
        Outcome baseline = execute(fixture(new LobbyPlayerAi("first", null)));
        MyRandom.setRandom(new Random(0));
        Recording recording = new Recording();
        Fixture teacher = fixture(new LobbyPlayerTeacher("first", recording));
        Outcome recorded = execute(teacher);
        assertEquals(recorded, baseline, "Teacher changed native state or RNG");
        assertNull(recording.pending);
        assertEquals(recording.entries.stream().map(entry -> ((Map<?, ?>) entry.request().get("question")).get("kind")).toList(),
                List.of("priority_mana", "mana_color", "priority_mana"));
        assertEquals(recording.entries.get(0).answer(), List.of(1));
        assertEquals(recording.entries.get(2).answer(), List.of(0));
        var childState = (Map<?, ?>) recording.entries.get(1).request().get("state");
        assertTrue(((List<?>) childState.get("cards")).stream()
                .anyMatch(item -> Boolean.TRUE.equals(((Map<?, ?>) item).get("tapped"))));
        MyRandom.setRandom(new Random(0));
        int[] cursor = {0};
        Fixture replay = fixture(new LobbyPlayerRl("first", request -> {
            Entry entry = recording.entries.get(cursor[0]++);
            assertEquals(request, entry.request(), "Mana request diverged");
            return entry.answer();
        }));
        assertNotSame(replay.land(), teacher.land());
        assertEquals(execute(replay).states(), recorded.states());
        assertEquals(cursor[0], 3);
        ((PlayerControllerTeacher) teacher.player().getController()).closeObservation();
        ((PlayerControllerRl) replay.player().getController()).closeObservation();
    }

    @Test public void clientCanPassOrChooseEitherColorWithNativeTapPayment() {
        for (int colorIndex = 0; colorIndex < 2; colorIndex++) {
            final int desired = colorIndex;
            List<Map<String, Object>> requests = new ArrayList<>();
            Fixture fixture = fixture(new LobbyPlayerRl("first", request -> {
                requests.add(request);
                var options = (Map<?, ?>) request.get("question");
                return List.of(options.get("kind").equals("mana_color") ? desired : requests.size() == 1 ? 0 : 1);
            }));
            var controller = fixture.player().getController();
            assertNull(controller.chooseSpellAbilityToPlay());
            assertFalse(fixture.land().isTapped());
            var selected = controller.chooseSpellAbilityToPlay().get(0);
            assertTrue(controller.playChosenSpellAbility(selected));
            var child = (Map<?, ?>) requests.get(2).get("question");
            String symbol = (String) ((List<?>) child.get("items")).get(desired);
            byte color = forge.card.MagicColor.fromName(symbol);
            assertEquals(fixture.player().getManaPool().getAmountOfColor(color), 1);
            assertEquals(fixture.player().getManaPool().totalMana(), 1);
            assertEquals(requests.size(), 3);
            assertTrue(fixture.land().isTapped());
            ((PlayerControllerRl) controller).closeObservation();
        }
    }

    @Test public void invalidAndUnboundManaDecisionsStopWithoutInventedMana() {
        Fixture unbound = fixture(new LobbyPlayerRl("first", request -> { throw new AssertionError("Unbound callback"); }));
        var root = unbound.land().getSpellAbilities().stream().filter(SpellAbility::isManaAbility).findFirst().orElseThrow();
        assertThrows(UnsupportedOperationException.class, () -> unbound.player().getController()
                .chooseColor("unbound", root, ColorSet.fromNames("U", "G")));
        assertThrows(UnsupportedOperationException.class, () -> unbound.player().getController().chooseSpellAbilityToPlay());
        for (List<Integer> invalid : List.of(List.<Integer>of(), List.of(-1), List.of(2), List.of(0, 1))) {
            Fixture bad = fixture(new LobbyPlayerRl("first", request -> {
                var options = (Map<?, ?>) request.get("question");
                return options.get("kind").equals("mana_color") ? invalid : List.of(1);
            }));
            var controller = bad.player().getController();
            var selected = controller.chooseSpellAbilityToPlay().get(0);
            assertThrows(IllegalArgumentException.class, () -> controller.playChosenSpellAbility(selected));
            assertEquals(bad.player().getManaPool().totalMana(), 0);
            assertThrows(IllegalArgumentException.class, controller::chooseSpellAbilityToPlay);
            ((PlayerControllerRl) controller).closeObservation();
        }
        Fixture stale = fixture(new LobbyPlayerRl("first", request -> List.of(1)));
        var controller = stale.player().getController();
        var selected = controller.chooseSpellAbilityToPlay().get(0);
        stale.game().getPhaseHandler().setPriority(stale.game().getRegisteredPlayers().get(1));
        assertThrows(IllegalStateException.class, () -> controller.playChosenSpellAbility(selected));
        assertFalse(stale.land().isTapped());
        assertEquals(stale.player().getManaPool().totalMana(), 0);
        List<Map<String, Object>> plannedRequests = new ArrayList<>();
        Fixture planned = fixture(new LobbyPlayerRl("first", request -> { plannedRequests.add(request); return List.of(1); }));
        var plannedController = planned.player().getController();
        var plannedAbility = plannedController.chooseSpellAbilityToPlay().get(0);
        plannedAbility.getManaPart().setExpressChoice("G");
        assertThrows(UnsupportedOperationException.class, () -> plannedController.playChosenSpellAbility(plannedAbility));
        assertEquals(plannedRequests.size(), 1, "Private mana plans must not become color options");
        assertFalse(planned.land().isTapped());
        assertEquals(planned.player().getManaPool().totalMana(), 0);
        ((PlayerControllerRl) plannedController).closeObservation();

        Fixture repeated = fixture(new LobbyPlayerRl("first", request -> {
            var options = (Map<?, ?>) request.get("question");
            return options.get("kind").equals("mana_color") ? List.of(0) : List.of(1);
        }));
        var repeatedController = repeated.player().getController();
        var repeatedAbility = repeatedController.chooseSpellAbilityToPlay().get(0);
        assertTrue(repeatedController.playChosenSpellAbility(repeatedAbility));
        assertThrows(IllegalArgumentException.class, () -> repeatedController.playChosenSpellAbility(repeatedAbility));
        assertEquals(repeated.player().getManaPool().totalMana(), 1);
        ((PlayerControllerRl) repeatedController).closeObservation();
        Fixture[] wrongMode = {null};
        wrongMode[0] = fixture(new LobbyPlayerRl("first", request -> {
            if (((Map<?, ?>) request.get("question")).get("kind").equals("mana_color")) {
                var nativeAbility = wrongMode[0].land().getSpellAbilities().stream()
                        .filter(SpellAbility::isManaAbility).findFirst().orElseThrow();
                assertThrows(UnsupportedOperationException.class, () -> wrongMode[0].player().getController()
                        .chooseModeForAbility(nativeAbility, List.of(), 1, 1, false));
                return List.of(0);
            }
            return List.of(1);
        }));
        var wrongController = wrongMode[0].player().getController();
        var wrongAbility = wrongController.chooseSpellAbilityToPlay().get(0);
        assertThrows(UnsupportedOperationException.class, () -> wrongController.playChosenSpellAbility(wrongAbility));
        assertThrows(UnsupportedOperationException.class, wrongController::chooseSpellAbilityToPlay);
        assertEquals(wrongMode[0].player().getManaPool().totalMana(), 0);
        ((PlayerControllerRl) wrongController).closeObservation();
        ((PlayerControllerRl) controller).closeObservation();
        ((PlayerControllerRl) unbound.player().getController()).closeObservation();
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toRealPath();
        for (var entry : Map.of(PlayerControllerAi.class, "forge-ai", Game.class, "forge-game", MyRandom.class, "forge-core").entrySet()) {
            assertEquals(Path.of(entry.getKey().getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath(),
                    root.resolve(entry.getValue() + "/target/classes").toRealPath());
        }
        OriginalCompositeChoice.initialize(root);
        var test = new ManaActivationTest();
        test.nativeManaActivationAsksForColorAfterTapWithoutNewPriority();
        test.teacherIsNeutralAndReplaysTheSubordinateColorWithoutAi();
        test.clientCanPassOrChooseEitherColorWithNativeTapPayment();
        test.invalidAndUnboundManaDecisionsStopWithoutInventedMana();
        System.out.println("PASS: native mana teacher/client/replay, color choices, state/RNG and errors verified");
    }
}
