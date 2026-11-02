package forge.rl;

import forge.rl.decisions.AttackersRecorder;

import com.google.common.eventbus.Subscribe;
import forge.LobbyPlayer;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.rl.decisions.AttackersDecision;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.event.GameEventAttackersDeclared;
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

public class AttackersTest {
    @BeforeClass public void initialize() { OriginalCompositeChoice.initialize(Path.of("..")); }

    record Fixture(Game game, Player actor, List<Card> creatures) { }
    record Result(List<Object> facts, List<Integer> attackers, List<Integer> defenders, List<Integer> events, long rng) { }

    public static final class Events {
        final List<Integer> sizes = new ArrayList<>();
        @Subscribe public void declared(GameEventAttackersDeclared event) { sizes.add(event.attackersMap().size()); }
    }

    static Fixture fixture(LobbyPlayer lobby, boolean guarded) {
        MyRandom.setRandom(new Random(0));
        var seats = List.of(new RegisteredPlayer(new Deck("first")).setPlayer(lobby),
                new RegisteredPlayer(new Deck("second")).setPlayer(new LobbyPlayerAi("second", null)));
        Game game = new Match(new GameRules(GameType.Constructed), seats, "attackers").createGame();
        game.getRegisteredPlayers().forEach(p -> p.setLife(20, null));
        game.setAge(GameStage.Play);
        Player actor = game.getPlayers().get(0);
        var phase = game.getPhaseHandler();
        phase.devModeSet(PhaseType.COMBAT_BEGIN, actor, false, 6);
        phase.setPriority(actor);
        List<Card> creatures = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Card card = battlefield(game, actor, "Grizzly Bears");
            card.setSickness(i == 2);
            card.setTapped(i == 3);
            creatures.add(card);
        }
        if (guarded) {
            for (int i = 0; i < 2; i++) { battlefield(game, game.getPlayers().get(1), "Craw Wurm").setSickness(false); }
        }
        phase.setCombat(new Combat(actor));
        phase.onStackResolved();
        return new Fixture(game, actor, List.copyOf(creatures));
    }

    private static Card battlefield(Game game, Player player, String name) {
        Card card = OriginalCompositeChoice.add(game, player, name);
        player.getZone(ZoneType.Hand).remove(card);
        player.getZone(ZoneType.Battlefield).add(card);
        return card;
    }

    static Result run(Fixture f) {
        var phase = f.game().getPhaseHandler();
        Events events = new Events();
        f.game().subscribeToEvents(events);
        phase.mainLoopStep();
        phase.mainLoopStep();
        assertEquals(phase.getPhase(), PhaseType.COMBAT_DECLARE_ATTACKERS);
        Combat combat = phase.getCombat();
        assertTrue(CombatUtil.validateAttackers(combat));
        assertFalse(combat.isAttacking(f.creatures().get(2)));
        assertFalse(combat.isAttacking(f.creatures().get(3)));
        for (Card card : combat.getAttackers()) {
            assertTrue(card.isTapped());
            assertSame(combat.getDefenderByAttacker(card), f.game().getPlayers().get(1));
        }
        if (!combat.getAttackers().isEmpty()) { phase.mainLoopStep(); }
        assertEquals(phase.getPhase(), PhaseType.COMBAT_DECLARE_ATTACKERS);
        assertEquals(events.sizes, List.of(combat.getAttackers().size()));
        assertTrue(f.game().getStack().isEmpty());
        assertFalse(f.game().isGameOver());
        return new Result(OriginalCompositeChoice.observe(f.game()),
                combat.getAttackers().stream().map(f.creatures()::indexOf).toList(),
                combat.getAttackers().stream().map(c -> f.game().getPlayers().indexOf(combat.getDefenderByAttacker(c))).toList(),
                List.copyOf(events.sizes), MyRandom.getRandom().nextLong());
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

    private static void second(Fixture f, boolean teacher, IDecisionRecordCallback recording, IPlayerDecisionCallback callback) {
        Player p = f.game().getPlayers().get(1);
        p.dangerouslySetController(teacher
                ? new PlayerControllerTeacher(f.game(), p, new LobbyPlayerAi("second", null), recording)
                : new PlayerControllerRl(f.game(), p, new LobbyPlayerAi("second", null), callback));
    }

    private static void close(Fixture f) {
        for (Player p : f.game().getPlayers()) {
            if (p.getController() instanceof PlayerControllerRl rl) { rl.closeObservation(); }
            if (p.getController() instanceof PlayerControllerTeacher teacher) { teacher.closeObservation(); }
        }
    }

    @Test public void teacherAndReplayUseFinalPairsAndNativePhaseTaps() {
        for (boolean guarded : List.of(false, true)) {
            Result expected = run(fixture(new LobbyPlayerAi("first", null), guarded));
            Recording recording = new Recording();
            var teacher = fixture(new LobbyPlayerTeacher("first", recording), guarded);
            second(teacher, true, recording, null);
            assertEquals(run(teacher), expected, "Teacher changed combat facts or RNG");
            assertNull(recording.pending);
            assertEquals(recording.answers.get(2), guarded ? List.of() : List.of(0, 0, 1, 0));
            assertEquals(((Map<?, ?>) recording.requests.get(2).get("options")).get("kind"), "declare_attackers");
            assertEquals(((List<?>) ((Map<?, ?>) recording.requests.get(2).get("options")).get("items")).size(), 2);
            int[] position = {0};
            IPlayerDecisionCallback callback = request -> {
                assertEquals(request, recording.requests.get(position[0]));
                return recording.answers.get(position[0]++);
            };
            var replay = fixture(new LobbyPlayerRl("first", callback), guarded);
            second(replay, false, null, callback);
            assertNotSame(replay.creatures().get(0), teacher.creatures().get(0));
            Result applied = run(replay);
            assertEquals(applied.facts(), expected.facts());
            assertEquals(applied.attackers(), expected.attackers());
            assertEquals(applied.defenders(), expected.defenders());
            assertEquals(applied.events(), expected.events());
            assertEquals(position[0], guarded ? 3 : 4);
            close(teacher); close(replay);
        }
    }

    private static void direct(Fixture f) {
        f.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, f.actor(), false, 6);
        f.game().getPhaseHandler().setCombat(new Combat(f.actor()));
    }

    @Test public void nativeLoopSkipsDeclarationWhenNoCreatureCanAttack() {
        var kinds = new ArrayList<Object>();
        IPlayerDecisionCallback callback = request -> {
            kinds.add(((Map<?, ?>) request.get("options")).get("kind"));
            return List.of(0);
        };
        var f = fixture(new LobbyPlayerRl("first", callback), false);
        second(f, false, null, callback);
        f.creatures().get(0).setTapped(true);
        f.creatures().get(1).setSickness(true);
        assertTrue(run(f).attackers().isEmpty());
        assertEquals(kinds, List.of("priority", "priority"));
        close(f);
    }

    @Test public void finalConfigurationRejectsLkiWithSameIdAsOfferedAttacker() {
        var f = fixture(new LobbyPlayerAi("first", null), false);
        direct(f);
        StateReader view = new StateReader(f.actor(), 0L);
        try {
            var request = new AttackersDecision(f.actor(), view, f.actor(), f.game().getCombat());
            Card offered = f.creatures().get(0);
            Card lki = CardCopyService.getLKICopy(offered);
            assertNotSame(lki, offered);
            assertEquals(lki, offered);
            f.game().getCombat().addAttacker(lki, f.game().getPlayers().get(1));
            assertThrows(IllegalArgumentException.class, () -> new AttackersRecorder(request).encode());
        } finally { view.close(); }
    }

    @Test public void invalidAndStalePairsStopBeforeAddingAttackers() {
        for (List<Integer> answer : List.of(List.of(0), List.of(0, 1), List.of(2, 0), List.of(0, 0, 0, 0), List.of(-1, 0))) {
            var f = fixture(new LobbyPlayerRl("first", request -> answer), false);
            direct(f);
            var controller = f.actor().getController();
            assertThrows(IllegalArgumentException.class, () -> controller.declareAttackers(f.actor(), f.game().getCombat()));
            assertTrue(f.game().getCombat().getAttackers().isEmpty());
            assertThrows(IllegalArgumentException.class, controller::chooseSpellAbilityToPlay);
            close(f);
        }
        for (boolean sickness : List.of(false, true)) {
            Fixture[] f = {null};
            f[0] = fixture(new LobbyPlayerRl("first", request -> {
                if (sickness) { f[0].creatures().get(0).setSickness(true); }
                else { f[0].creatures().get(0).setTapped(true); }
                return List.of(0, 0);
            }), false);
            direct(f[0]);
            assertThrows(IllegalArgumentException.class, () -> f[0].actor().getController().declareAttackers(f[0].actor(), f[0].game().getCombat()));
            assertTrue(f[0].game().getCombat().getAttackers().isEmpty());
            close(f[0]);
        }
        var foreign = fixture(new LobbyPlayerRl("first", request -> { throw new AssertionError("Invalid context reached client"); }), false);
        direct(foreign);
        assertThrows(IllegalArgumentException.class, () -> foreign.actor().getController().declareAttackers(foreign.actor(), new Combat(foreign.actor())));
        close(foreign);
        var unsupported = fixture(new LobbyPlayerRl("first", request -> { throw new AssertionError("Unsupported scope reached client"); }), false);
        battlefield(unsupported.game(), unsupported.actor(), "Llanowar Elves");
        direct(unsupported);
        assertThrows(UnsupportedOperationException.class, () -> unsupported.actor().getController().declareAttackers(unsupported.actor(), unsupported.game().getCombat()));
        close(unsupported);
    }

    @Test public void publicBoundaryCapturesCompleteFinalCombatWithoutChangingOriginal() {
        for (boolean guarded : List.of(false, true)) {
            var baseline = fixture(new LobbyPlayerAi("first", null), guarded);
            Result expected = run(baseline);
            assertEquals(expected.attackers().size(), guarded ? 0 : 2);
            var capture = fixture(new LobbyPlayerAi("first", null), guarded);
            List<Map<Card, GameEntity>> configurations = new ArrayList<>();
            capture.actor().dangerouslySetController(new PlayerControllerAi(capture.game(), capture.actor(), new LobbyPlayerAi("first", null)) {
                @Override public void declareAttackers(Player attacker, Combat combat) {
                    assertTrue(combat.getAttackers().isEmpty());
                    assertEquals(CombatUtil.getPossibleAttackers(attacker).size(), 2);
                    super.declareAttackers(attacker, combat);
                    configurations.add(Map.copyOf(combat.getAttackersAndDefenders()));
                }
            });
            assertEquals(run(capture), expected);
            assertEquals(configurations.size(), 1);
            var assignments = capture.game().getCombat().getAttackers().stream()
                    .map(card -> List.of(capture.creatures().indexOf(card),
                            capture.game().getPlayers().indexOf(configurations.get(0).get(card)))).toList();
            var replay = fixture(new LobbyPlayerAi("first", null), guarded);
            replay.actor().dangerouslySetController(new PlayerControllerAi(replay.game(), replay.actor(), new LobbyPlayerAi("first", null)) {
                @Override public void declareAttackers(Player attacker, Combat combat) {
                    for (var pair : assignments) {
                        combat.addAttacker(replay.creatures().get(pair.get(0)), replay.game().getPlayers().get(pair.get(1)));
                    }
                }
            });
            Result applied = run(replay);
            assertEquals(applied.facts(), expected.facts());
            assertEquals(applied.attackers(), expected.attackers());
            assertEquals(applied.defenders(), expected.defenders());
        }
    }

    public static void main(String[] args) {
        OriginalCompositeChoice.initialize(Path.of(args[0]));
        var test = new AttackersTest();
        test.publicBoundaryCapturesCompleteFinalCombatWithoutChangingOriginal();
        test.teacherAndReplayUseFinalPairsAndNativePhaseTaps();
        test.nativeLoopSkipsDeclarationWhenNoCreatureCanAttack();
        test.finalConfigurationRejectsLkiWithSameIdAsOfferedAttacker();
        test.invalidAndStalePairsStopBeforeAddingAttackers();
        System.out.println("PASS: native attackers public boundary, teacher neutrality and replay verified");
    }
}
