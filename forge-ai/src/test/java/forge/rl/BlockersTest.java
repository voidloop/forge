package forge.rl;


import com.google.common.eventbus.Subscribe;
import forge.LobbyPlayer;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.rl.requests.BlockersRequest;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.event.GameEventBlockersDeclared;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.*;

import static org.testng.Assert.*;

public class BlockersTest {
    @BeforeClass public void initialize() { OriginalCompositeChoice.initialize(Path.of("..")); }

    record Fixture(Game game, Player defender, List<Card> attackers, List<Card> blockers) { }
    record Result(List<Object> facts, List<Integer> pairs, int events, long rng) { }
    public static final class Events {
        int count;
        @Subscribe public void declared(GameEventBlockersDeclared event) { count++; }
    }

    static Fixture fixture(LobbyPlayer first, LobbyPlayer second, int scenario) {
        MyRandom.setRandom(new Random(0));
        var seats = List.of(new RegisteredPlayer(new Deck("first")).setPlayer(first),
                new RegisteredPlayer(new Deck("second")).setPlayer(second));
        Game game = new Match(new GameRules(GameType.Constructed), seats, "blockers").createGame();
        game.getRegisteredPlayers().forEach(p -> p.setLife(20, null));
        game.setAge(GameStage.Play);
        Player actor = game.getPlayers().get(0), defender = game.getPlayers().get(1);
        if (scenario == 2) { defender.setLife(5, null); }
        var phase = game.getPhaseHandler();
        phase.devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, actor, false, 6);
        phase.setPriority(actor);
        Combat combat = new Combat(actor);
        phase.setCombat(combat);
        List<Card> attackers = new ArrayList<>(), blockers = new ArrayList<>();
        for (int i = 0; i < (scenario == 1 ? 2 : 1); i++) {
            Card card = battlefield(game, actor, scenario == 1 ? "Grizzly Bears" : "Craw Wurm");
            card.setSickness(false); card.setTapped(true);
            attackers.add(card); combat.addAttacker(card, defender);
        }
        for (int i = 0; i < 4; i++) {
            Card card = battlefield(game, defender, scenario == 1 ? "Craw Wurm" : "Grizzly Bears");
            card.setSickness(true); card.setTapped(i == 3);
            blockers.add(card);
        }
        if (scenario == 0) { blockers.get(1).setTapped(true); blockers.get(2).setTapped(true); }
        phase.onStackResolved();
        return new Fixture(game, defender, List.copyOf(attackers), List.copyOf(blockers));
    }

    private static Card battlefield(Game game, Player player, String name) {
        Card card = OriginalCompositeChoice.add(game, player, name);
        player.getZone(ZoneType.Hand).remove(card);
        player.getZone(ZoneType.Battlefield).add(card);
        return card;
    }

    static List<Integer> pairs(Fixture f) {
        List<Integer> result = new ArrayList<>();
        for (Card attacker : f.game().getCombat().getAttackers()) {
            for (Card blocker : f.game().getCombat().getBlockers(attacker)) {
                result.add(f.blockers().indexOf(blocker)); result.add(f.attackers().indexOf(attacker));
            }
        }
        return result;
    }

    static Result run(Fixture f) {
        Events events = new Events(); f.game().subscribeToEvents(events);
        var phase = f.game().getPhaseHandler();
        phase.mainLoopStep(); phase.mainLoopStep();
        assertEquals(phase.getPhase(), PhaseType.COMBAT_DECLARE_BLOCKERS);
        assertNull(CombatUtil.validateBlocks(f.game().getCombat(), f.defender()));
        assertFalse(f.game().getCombat().isBlocking(f.blockers().get(3)));
        phase.mainLoopStep();
        assertEquals(phase.getPhase(), PhaseType.COMBAT_DECLARE_BLOCKERS);
        assertEquals(events.count, 1);
        assertTrue(f.game().getStack().isEmpty()); assertFalse(f.game().isGameOver());
        return new Result(OriginalCompositeChoice.observe(f.game()), pairs(f), events.count, MyRandom.getRandom().nextLong());
    }

    @Test public void publicBoundaryCapturesFinalBlocksWithoutChangingOriginal() {
        for (int scenario = 0; scenario < 3; scenario++) {
            var baseline = fixture(new LobbyPlayerAi("first", null), new LobbyPlayerAi("second", null), scenario);
            Result expected = run(baseline);
            assertEquals(expected.pairs(), scenario == 0 ? List.of() : scenario == 1 ? List.of(0, 0, 1, 1) : List.of(0, 0, 1, 0));
            var capture = fixture(new LobbyPlayerAi("first", null), new LobbyPlayerAi("second", null), scenario);
            List<List<Integer>> configurations = new ArrayList<>();
            capture.defender().dangerouslySetController(new PlayerControllerAi(capture.game(), capture.defender(), new LobbyPlayerAi("second", null)) {
                @Override public void declareBlockers(Player defender, Combat combat) {
                    assertTrue(combat.getAllBlockers().isEmpty());
                    assertTrue(CombatUtil.canBlock(defender, combat));
                    super.declareBlockers(defender, combat);
                    configurations.add(pairs(capture));
                }
            });
            assertEquals(run(capture), expected);
            assertEquals(configurations, List.of(expected.pairs()));
            var replay = fixture(new LobbyPlayerAi("first", null), new LobbyPlayerAi("second", null), scenario);
            replay.defender().dangerouslySetController(new PlayerControllerAi(replay.game(), replay.defender(), new LobbyPlayerAi("second", null)) {
                @Override public void declareBlockers(Player defender, Combat combat) {
                    for (int i = 0; i < expected.pairs().size(); i += 2) {
                        combat.addBlocker(replay.attackers().get(expected.pairs().get(i + 1)), replay.blockers().get(expected.pairs().get(i)));
                    }
                }
            });
            Result applied = run(replay);
            assertEquals(applied.facts(), expected.facts());
            assertEquals(applied.pairs(), expected.pairs());
            assertEquals(applied.events(), expected.events());
        }
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

    private static void close(Fixture f) {
        for (Player p : f.game().getPlayers()) {
            if (p.getController() instanceof PlayerControllerRl rl) { rl.closeObservation(); }
            if (p.getController() instanceof PlayerControllerTeacher teacher) { teacher.closeObservation(); }
        }
    }

    @Test public void teacherAndReplayUseFinalPairsInNativeLoop() {
        for (int scenario = 0; scenario < 3; scenario++) {
            Result expected = run(fixture(new LobbyPlayerAi("first", null), new LobbyPlayerAi("second", null), scenario));
            Recording recording = new Recording();
            var teacher = fixture(new LobbyPlayerTeacher("first", recording), new LobbyPlayerTeacher("second", recording), scenario);
            assertEquals(run(teacher), expected, "Teacher changed combat facts or RNG");
            assertNull(recording.pending);
            assertEquals(recording.answers.get(2), expected.pairs());
            Map<?, ?> options = (Map<?, ?>) recording.requests.get(2).get("question");
            assertEquals(options.get("kind"), "declare_blockers");
            assertEquals(((List<?>) options.get("items")).size(), scenario == 0 ? 1 : 3);
            int[] position = {0};
            IPlayerDecisionCallback callback = request -> {
                assertEquals(request, recording.requests.get(position[0]));
                return recording.answers.get(position[0]++);
            };
            var replay = fixture(new LobbyPlayerRl("first", callback), new LobbyPlayerRl("second", callback), scenario);
            assertNotSame(replay.blockers().get(0), teacher.blockers().get(0));
            Result applied = run(replay);
            assertEquals(applied.facts(), expected.facts());
            assertEquals(applied.pairs(), expected.pairs());
            assertEquals(applied.events(), expected.events());
            assertEquals(position[0], 4);
            close(teacher); close(replay);
        }
    }

    @Test public void nativeLoopSkipsQuestionWhenNoCreatureCanBlock() {
        var kinds = new ArrayList<Object>();
        IPlayerDecisionCallback callback = request -> {
            kinds.add(((Map<?, ?>) request.get("question")).get("kind")); return List.of(0);
        };
        var f = fixture(new LobbyPlayerRl("first", callback), new LobbyPlayerRl("second", callback), 1);
        f.blockers().forEach(c -> c.setTapped(true));
        assertTrue(run(f).pairs().isEmpty());
        assertEquals(kinds, List.of("priority", "priority", "priority"));
        close(f);
    }

    private static void direct(Fixture f) {
        f.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, f.game().getPlayers().get(0), false, 6);
    }

    @Test public void invalidStaleAndForeignAssignmentsStopTheController() {
        for (List<Integer> answer : List.of(List.of(0), List.of(0, 2), List.of(3, 0), List.of(0, 0, 0, 1), List.of(-1, 0))) {
            var f = fixture(new LobbyPlayerAi("first", null), new LobbyPlayerRl("second", request -> answer), 1);
            direct(f);
            assertThrows(IllegalArgumentException.class, () -> f.defender().getController().declareBlockers(f.defender(), f.game().getCombat()));
            assertTrue(f.game().getCombat().getAllBlockers().isEmpty());
            assertThrows(IllegalArgumentException.class, f.defender().getController()::chooseSpellAbilityToPlay);
            close(f);
        }
        for (int mutation = 0; mutation < 4; mutation++) {
            int kind = mutation;
            Fixture[] f = {null};
            f[0] = fixture(new LobbyPlayerAi("first", null), new LobbyPlayerRl("second", request -> {
                if (kind == 0) { f[0].blockers().get(0).setTapped(true); }
                if (kind == 1) { f[0].blockers().get(0).setController(f[0].game().getPlayers().get(0), 0); }
                if (kind == 2) { f[0].game().getCombat().removeFromCombat(f[0].attackers().get(0)); }
                if (kind == 3) { f[0].game().getCombat().addBlocker(f[0].attackers().get(1), f[0].blockers().get(2)); }
                return List.of(0, 0);
            }), 1);
            direct(f[0]);
            assertThrows(IllegalArgumentException.class, () -> f[0].defender().getController().declareBlockers(f[0].defender(), f[0].game().getCombat()));
            assertFalse(f[0].game().getCombat().isBlocking(f[0].blockers().get(0)));
            close(f[0]);
        }
        var foreign = fixture(new LobbyPlayerAi("first", null), new LobbyPlayerRl("second", request -> { throw new AssertionError("Invalid context reached client"); }), 1);
        direct(foreign);
        assertThrows(IllegalArgumentException.class, () -> foreign.defender().getController().declareBlockers(foreign.defender(), new Combat(foreign.game().getPlayers().get(0))));
        close(foreign);
        var unsupported = fixture(new LobbyPlayerAi("first", null), new LobbyPlayerRl("second", request -> { throw new AssertionError("Unsupported scope reached client"); }), 1);
        battlefield(unsupported.game(), unsupported.defender(), "Llanowar Elves"); direct(unsupported);
        assertThrows(UnsupportedOperationException.class, () -> unsupported.defender().getController().declareBlockers(unsupported.defender(), unsupported.game().getCombat()));
        close(unsupported);
    }

    @Test public void finalConfigurationRejectsLkiBlockerAndAttackerWithSameId() {
        for (boolean attacker : List.of(false, true)) {
            var f = fixture(new LobbyPlayerAi("first", null), new LobbyPlayerAi("second", null), 1);
            direct(f);
            StateReader view = new StateReader(f.defender(), 0L);
            try {
                var request = new BlockersRequest(f.defender(), f.defender(), f.game().getCombat());
                Card offered = attacker ? f.attackers().get(0) : f.blockers().get(0);
                Card lki = CardCopyService.getLKICopy(offered);
                assertNotSame(lki, offered); assertEquals(lki, offered);
                if (attacker) {
                    f.game().getCombat().removeFromCombat(offered);
                    f.game().getCombat().addAttacker(lki, f.defender());
                } else { f.game().getCombat().addBlocker(f.attackers().get(0), lki); }
                assertThrows(IllegalArgumentException.class, () -> request.encodeAnswer(null));
            } finally { view.close(); }
        }
    }

    public static void main(String[] args) {
        OriginalCompositeChoice.initialize(Path.of(args[0]));
        var test = new BlockersTest();
        test.publicBoundaryCapturesFinalBlocksWithoutChangingOriginal();
        test.teacherAndReplayUseFinalPairsInNativeLoop();
        test.nativeLoopSkipsQuestionWhenNoCreatureCanBlock();
        test.invalidStaleAndForeignAssignmentsStopTheController();
        test.finalConfigurationRejectsLkiBlockerAndAttackerWithSameId();
        System.out.println("PASS: native blockers public boundary, teacher neutrality and replay verified");
    }
}
