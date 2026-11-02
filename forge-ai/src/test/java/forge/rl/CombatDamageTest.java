package forge.rl;

import forge.rl.decisions.CombatDamageRecorder;
import forge.rl.decisions.CombatGraveyardRecorder;
import forge.rl.decisions.CombatDamageExecutor;
import forge.rl.decisions.CombatGraveyardExecutor;

import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.CardCollection;
import forge.game.card.CardCopyService;
import forge.rl.decisions.CombatDamageDecision;
import forge.rl.decisions.CombatGraveyardDecision;
import forge.game.phase.PhaseType;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.*;

import static org.testng.Assert.*;

public class CombatDamageTest {
    @BeforeClass public void initialize() { OriginalCompositeChoice.initialize(Path.of("..")); }
    record Result(List<Object> damage, List<Object> main2, long rng) { }
    record Order(List<Integer> cards, List<Integer> chosen) { }
    record Assignment(int source, List<Integer> targets, int damage, boolean override, List<Integer> answer) { }

    static List<Card> cards(BlockersTest.Fixture f) {
        var cards = new ArrayList<>(f.attackers()); cards.addAll(f.blockers()); return cards;
    }

    static Result run(BlockersTest.Fixture f) {
        var phase = f.game().getPhaseHandler();
        phase.mainLoopStep(); phase.mainLoopStep();
        assertEquals(phase.getPhase(), PhaseType.COMBAT_DECLARE_BLOCKERS);
        for (int i = 0; i < 4 && phase.getPhase() != PhaseType.COMBAT_DAMAGE; i++) { phase.mainLoopStep(); }
        assertEquals(phase.getPhase(), PhaseType.COMBAT_DAMAGE);
        phase.mainLoopStep(); // Native state-based actions precede the new priority request.
        List<Object> damage = OriginalCompositeChoice.observe(f.game());
        // Ask for priority after damage, then pass through end of combat into MAIN2.
        for (int i = 0; i < 4 && phase.getPhase() != PhaseType.MAIN2; i++) { phase.mainLoopStep(); }
        phase.mainLoopStep();
        assertEquals(phase.getPhase(), PhaseType.MAIN2);
        assertTrue(f.game().getStack().isEmpty()); assertFalse(f.game().isGameOver());
        assertNull(f.game().getCombat());
        return new Result(damage, OriginalCompositeChoice.observe(f.game()), MyRandom.getRandom().nextLong());
    }

    @Test public void publicBoundaryCapturesFinalAssignmentsWithoutChangingOriginal() {
        for (int scenario = 0; scenario < 3; scenario++) {
            var baseline = BlockersTest.fixture(new LobbyPlayerAi("first", null), new LobbyPlayerAi("second", null), scenario);
            Result expected = run(baseline);
            assertEquals(baseline.defender().getLife(), scenario == 0 ? 14 : scenario == 2 ? 5 : 20);
            assertEquals(baseline.game().getCardsIn(ZoneType.Graveyard).size(), scenario == 0 ? 0 : scenario == 1 ? 2 : 3);
            var capture = BlockersTest.fixture(new LobbyPlayerAi("first", null), new LobbyPlayerAi("second", null), scenario);
            List<Assignment> assignments = new ArrayList<>();
            List<Order> orders = new ArrayList<>();
            for (var player : capture.game().getPlayers()) {
                player.dangerouslySetController(new PlayerControllerAi(capture.game(), player, new LobbyPlayerAi(player.getName(), null)) {
                    @Override public Map<Card, Integer> assignCombatDamage(Card source, CardCollectionView targets,
                            CardCollectionView remaining, int damage, GameEntity defender, boolean override) {
                        assertFalse(capture.game().getRules().hasOrderCombatants()); assertTrue(override);
                        assertEquals(remaining == null, defender == null);
                        List<Integer> bindings = targets.stream().map(cards(capture)::indexOf).toList();
                        var chosen = super.assignCombatDamage(source, targets, remaining, damage, defender, override);
                        assertNotNull(chosen); assertFalse(chosen.containsKey(null));
                        assertEquals(chosen.values().stream().mapToInt(Integer::intValue).sum(), damage);
                        var answer = new ArrayList<Integer>();
                        for (int i = 0; i < targets.size(); i++) {
                            if (chosen.containsKey(targets.get(i))) { answer.add(i); answer.add(chosen.get(targets.get(i))); }
                        }
                        assignments.add(new Assignment(cards(capture).indexOf(source), bindings, damage, override, List.copyOf(answer)));
                        return chosen;
                    }
                    @Override public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destination, forge.game.spellability.SpellAbility ability) {
                        assertEquals(destination, ZoneType.Graveyard); assertNull(ability);
                        var bindings = List.copyOf(cards);
                        var chosen = super.orderMoveToZoneList(cards, destination, ability);
                        orders.add(new Order(bindings.stream().map(cards(capture)::indexOf).toList(),
                                chosen.stream().map(bindings::indexOf).toList()));
                        return chosen;
                    }
                    @Override public forge.game.card.CardCollection orderBlockers(Card attacker, forge.game.card.CardCollection blockers) {
                        throw new AssertionError("Native default rules unexpectedly requested legacy ordering");
                    }
                });
            }
            assertEquals(run(capture), expected);
            assertEquals(assignments.size(), scenario == 0 ? 0 : scenario == 1 ? 4 : 3);
            if (scenario == 2) { assertEquals(assignments.get(0).targets().size(), 2); }
            var replay = BlockersTest.fixture(new LobbyPlayerAi("first", null), new LobbyPlayerAi("second", null), scenario);
            int[] position = {0}, orderPosition = {0};
            for (var player : replay.game().getPlayers()) {
                player.dangerouslySetController(new PlayerControllerAi(replay.game(), player, new LobbyPlayerAi(player.getName(), null)) {
                    @Override public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destination, forge.game.spellability.SpellAbility ability) {
                        var order = orders.get(orderPosition[0]++);
                        assertEquals(cards.stream().map(cards(replay)::indexOf).toList(), order.cards());
                        return new CardCollection(order.chosen().stream().map(cards::get).toList());
                    }
                    @Override public Map<Card, Integer> assignCombatDamage(Card source, CardCollectionView targets,
                            CardCollectionView remaining, int damage, GameEntity defender, boolean override) {
                        var chosen = assignments.get(position[0]++);
                        assertEquals(cards(replay).indexOf(source), chosen.source());
                        assertEquals(targets.stream().map(cards(replay)::indexOf).toList(), chosen.targets());
                        assertEquals(damage, chosen.damage()); assertEquals(override, chosen.override());
                        var applied = new LinkedHashMap<Card, Integer>();
                        for (int i = 0; i < chosen.answer().size(); i += 2) {
                            applied.put(targets.get(chosen.answer().get(i)), chosen.answer().get(i + 1));
                        }
                        return applied;
                    }
                });
            }
            Result applied = run(replay);
            assertEquals(applied.damage(), expected.damage()); assertEquals(applied.main2(), expected.main2());
            assertEquals(position[0], assignments.size());
            assertEquals(orderPosition[0], scenario == 0 ? 0 : 1);
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

    private static void close(BlockersTest.Fixture f) {
        for (var player : f.game().getPlayers()) {
            if (player.getController() instanceof PlayerControllerRl rl) { rl.closeObservation(); }
            if (player.getController() instanceof PlayerControllerTeacher teacher) { teacher.closeObservation(); }
        }
    }

    @Test public void teacherAndReplayApplyFinalDamageOnFreshGames() {
        for (int scenario = 0; scenario < 3; scenario++) {
            Result expected = run(BlockersTest.fixture(new LobbyPlayerAi("first", null), new LobbyPlayerAi("second", null), scenario));
            var recording = new Recording();
            var teacher = BlockersTest.fixture(new LobbyPlayerTeacher("first", recording), new LobbyPlayerTeacher("second", recording), scenario);
            assertEquals(run(teacher), expected, "Teacher changed facts or RNG");
            assertNull(recording.pending);
            assertEquals(recording.requests.stream().filter(r -> ((Map<?, ?>) r.get("options")).get("kind").equals("combat_damage")).count(),
                    scenario == 0 ? 0L : scenario == 1 ? 4L : 3L);
            int[] position = {0};
            IPlayerDecisionCallback callback = request -> {
                assertEquals(request, recording.requests.get(position[0]));
                return recording.answers.get(position[0]++);
            };
            var replay = BlockersTest.fixture(new LobbyPlayerRl("first", callback), new LobbyPlayerRl("second", callback), scenario);
            assertNotSame(replay.attackers().get(0), teacher.attackers().get(0));
            Result applied = run(replay);
            assertEquals(applied.damage(), expected.damage()); assertEquals(applied.main2(), expected.main2());
            assertEquals(position[0], recording.requests.size());
            close(teacher); close(replay);
        }
    }

    @Test public void clientCanOverassignOrSplitWithoutLethalOrdering() {
        for (List<Integer> answer : List.of(List.of(1, 6), List.of(0, 1, 1, 5))) {
            IPlayerDecisionCallback callback = request -> {
                var options = (Map<?, ?>) request.get("options");
                return switch ((String) options.get("kind")) {
                    case "priority" -> List.of(0);
                    case "declare_blockers" -> List.of(0, 0, 1, 0);
                    case "combat_graveyard_order" -> List.of(1, 0);
                    case "combat_damage" -> ((Number) options.get("damage")).intValue() == 6 ? answer : List.of(0, 2);
                    default -> throw new AssertionError("Unexpected native question");
                };
            };
            var f = BlockersTest.fixture(new LobbyPlayerRl("first", callback), new LobbyPlayerRl("second", callback), 2);
            run(f);
            assertEquals(f.game().getCardsIn(ZoneType.Graveyard).size(), 2);
            assertEquals(f.defender().getLife(), 5);
            Card survivor = f.game().getCardState(f.blockers().get(0), null);
            assertTrue(survivor.isInZone(ZoneType.Battlefield));
            assertEquals(survivor.getDamage(), answer.size() == 2 ? 0 : 1);
            close(f);
        }
    }

    private static BlockersTest.Fixture ready() {
        var f = BlockersTest.fixture(new LobbyPlayerAi("first", null), new LobbyPlayerAi("second", null), 2);
        f.game().getPhaseHandler().mainLoopStep(); f.game().getPhaseHandler().mainLoopStep();
        f.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DAMAGE, f.attackers().get(0).getController(), false, 6);
        return f;
    }

    @Test public void invalidAndStaleMapsStopBeforeNativeDamage() {
        for (List<Integer> answer : List.of(List.<Integer>of(), List.of(0), List.of(0, -6), List.of(2, 6),
                List.of(0, 3, 0, 3), List.of(0, 7), List.of(0, Integer.MAX_VALUE, 1, Integer.MAX_VALUE))) {
            var f = ready(); var player = f.attackers().get(0).getController();
            var rl = new PlayerControllerRl(f.game(), player, new LobbyPlayerRl("first", r -> answer), r -> answer);
            player.dangerouslySetController(rl);
            assertThrows(IllegalArgumentException.class, () -> rl.assignCombatDamage(f.attackers().get(0),
                    new CardCollection(f.game().getCombat().getBlockers(f.attackers().get(0))),
                    f.game().getCombat().getAttackers(), 6, f.defender(), true));
            assertTrue(cards(f).stream().allMatch(c -> c.getDamage() == 0 && c.getTotalAssignedDamage() == 0));
            assertThrows(IllegalArgumentException.class, rl::chooseSpellAbilityToPlay);
            close(f);
        }
        var f = ready(); var player = f.attackers().get(0).getController();
        var view = new StateReader(player, 0L);
        var targets = new CardCollection(f.game().getCombat().getBlockers(f.attackers().get(0)));
        try {
            var request = new CombatDamageDecision(player, view, f.attackers().get(0), targets,
                    f.game().getCombat().getAttackers(), 6, f.defender(), true);
            var lki = CardCopyService.getLKICopy(targets.get(0));
            assertEquals(lki, targets.get(0)); assertNotSame(lki, targets.get(0));
            assertThrows(IllegalArgumentException.class, () -> new CombatDamageRecorder(request, Map.of(lki, 6)).encode());
            assertThrows(UnsupportedOperationException.class, () -> new CombatDamageRecorder(request, null).encode());
            assertThrows(IllegalArgumentException.class, () -> new CombatDamageRecorder(request, Collections.singletonMap(null, 6)).encode());
            f.game().getCombat().removeFromCombat(targets.get(0));
            assertThrows(IllegalArgumentException.class, () -> new CombatDamageExecutor(request, List.of(0, 6)).apply());
        } finally { view.close(); }
    }

    @Test public void graveyardOrderRequiresCompletePermutationAndLiveDeathContext() {
        var f = ready(); var view = new StateReader(f.defender(), 0L);
        var dead = new CardCollection(f.blockers().subList(0, 2));
        dead.forEach(c -> c.setDamage(2));
        try {
            var request = new CombatGraveyardDecision(f.defender(), view, dead, ZoneType.Graveyard, null);
            assertEquals(List.copyOf(new CombatGraveyardExecutor(request, List.of(1, 0)).apply()), List.of(dead.get(1), dead.get(0)));
            for (List<Integer> answer : List.of(List.of(0), List.of(0, 0), List.of(0, 2))) {
                assertThrows(IllegalArgumentException.class, () -> new CombatGraveyardExecutor(request, answer).apply());
            }
            assertThrows(IllegalArgumentException.class, () -> new CombatGraveyardRecorder(request, new CardCollection(
                    List.of(CardCopyService.getLKICopy(dead.get(0)), dead.get(1)))).encode());
            assertThrows(UnsupportedOperationException.class, () -> new CombatGraveyardDecision(f.defender(), view, dead, ZoneType.Library, null));
            dead.get(0).setDamage(0);
            assertThrows(IllegalArgumentException.class, () -> new CombatGraveyardExecutor(request, List.of(0, 1)).apply());
            dead.get(0).setDamage(2);
            f.game().getPhaseHandler().setCombat(new forge.game.combat.Combat(f.attackers().get(0).getController()));
            assertThrows(IllegalArgumentException.class, () -> new CombatGraveyardRecorder(request, dead).encode());
        } finally { view.close(); }
    }

    public static void main(String[] args) {
        OriginalCompositeChoice.initialize(Path.of(args[0]));
        var test = new CombatDamageTest();
        test.publicBoundaryCapturesFinalAssignmentsWithoutChangingOriginal();
        test.teacherAndReplayApplyFinalDamageOnFreshGames();
        test.clientCanOverassignOrSplitWithoutLethalOrdering();
        test.invalidAndStaleMapsStopBeforeNativeDamage();
        test.graveyardOrderRequiresCompletePermutationAndLiveDeathContext();
        System.out.println("PASS: native combat damage public boundary, teacher neutrality and replay verified");
    }
}
