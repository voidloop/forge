package forge.rl;

import forge.LobbyPlayer;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.card.Card;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.*;

import static org.testng.Assert.*;

public class LyraCastingTest {
    @BeforeClass public void initialize() { OriginalCompositeChoice.initialize(Path.of("..")); }

    private static Card battlefield(ManaPaymentBoundaryTest.Fixture f, Player player, String name) {
        Card card = OriginalCompositeChoice.add(f.game(), player, name);
        player.getZone(ZoneType.Hand).remove(card);
        player.getZone(ZoneType.Battlefield).add(card);
        return card;
    }

    private static ManaPaymentBoundaryTest.Fixture fixture(LobbyPlayer lobby) {
        var f = ManaPaymentBoundaryTest.fixture(lobby, false);
        f.player().getZone(ZoneType.Hand).remove(f.spell().getHostCard());
        f.lands().forEach(card -> f.player().getZone(ZoneType.Battlefield).remove(card));
        List<Card> lands = new ArrayList<>();
        for (String name : List.of("Forest", "Forest", "Forest", "Plains", "Plains")) {
            lands.add(battlefield(f, f.player(), name));
        }
        var spell = OriginalCompositeChoice.add(f.game(), f.player(), "Lyra Dawnbringer").getFirstSpellAbility();
        spell.setActivatingPlayer(f.player());
        Player opponent = f.game().getPlayers().get(1);
        opponent.dangerouslySetController(new PlayerControllerAi(f.game(), opponent, new LobbyPlayerAi("second", null)));
        battlefield(f, f.player(), "Angel of Retribution");
        battlefield(f, f.player(), "Grizzly Bears");
        battlefield(f, opponent, "Angel of Retribution");
        f.game().getPhaseHandler().devModeSet(PhaseType.MAIN1, f.player(), false, 6);
        return new ManaPaymentBoundaryTest.Fixture(f.game(), f.player(), spell, List.copyOf(lands));
    }

    private record Outcome(List<Object> facts, List<Object> creatures, List<String> spent, List<Integer> spentSources,
                           List<Integer> activated, List<String> events, long rng) { }

    private static Outcome run(ManaPaymentBoundaryTest.Fixture f) {
        var phase = f.game().getPhaseHandler();
        var events = new CastingPriorityTest.Events(f);
        f.game().subscribeToEvents(events);
        phase.onStackResolved();
        phase.mainLoopStep();
        assertSame(f.game().getStack().peekAbility(), f.spell());
        var spent = f.spell().getPayingMana().stream().map(Object::toString).toList();
        assertEquals(spent.size(), 5);
        assertEquals(Collections.frequency(spent, "W"), 2);
        assertEquals(Collections.frequency(spent, "G"), 3);
        var sourceIds = f.lands().stream().map(Card::getId).toList();
        var spentSources = f.spell().getPayingMana().stream().map(mana -> sourceIds.indexOf(mana.getSourceCard().getId())).toList();
        assertEquals(new HashSet<>(spentSources), Set.of(0, 1, 2, 3, 4));
        var activated = f.spell().getPayingManaAbilities().stream().map(sa -> f.lands().indexOf(sa.getHostCard())).toList();
        assertEquals(new HashSet<>(activated), Set.of(0, 1, 2, 3, 4));
        assertTrue(f.lands().stream().allMatch(Card::isTapped));
        assertTrue(f.player().getManaPool().isEmpty());
        phase.mainLoopStep();
        assertTrue(f.game().getStack().isEmpty());
        Card lyra = f.game().getCardState(f.spell().getHostCard());
        assertTrue(lyra.isInZone(ZoneType.Battlefield));
        assertEquals(lyra.getNetPower(), 5);
        assertEquals(lyra.getNetToughness(), 5);
        assertTrue(lyra.hasSickness());
        assertFalse(lyra.isTapped());
        assertEquals(lyra.getDamage(), 0);
        assertTrue(lyra.hasKeyword(Keyword.FLYING));
        assertTrue(lyra.hasKeyword(Keyword.FIRST_STRIKE));
        assertTrue(lyra.hasKeyword(Keyword.LIFELINK));
        List<Object> creatures = new ArrayList<>();
        for (Player player : f.game().getPlayers()) {
            for (Card card : player.getCreaturesInPlay()) {
                boolean buffed = card.getName().equals("Angel of Retribution") && player == f.player();
                int power = card.getName().equals("Grizzly Bears") ? 2 : buffed ? 6 : 5;
                assertEquals(card.getNetPower(), power);
                assertEquals(card.getNetToughness(), power);
                assertEquals(card.hasKeyword(Keyword.LIFELINK), buffed || card == lyra);
                creatures.add(List.of(card.getName(), card.getNetPower(), card.getNetToughness(),
                        card.getKeywords().stream().map(k -> k.getKeyword().toString()).sorted().toList()));
            }
        }
        assertSame(phase.getPriorityPlayer(), f.player());
        phase.mainLoopStep();
        assertSame(phase.getPriorityPlayer(), f.game().getPlayers().get(1));
        assertEquals(phase.getPhase(), PhaseType.MAIN1);
        assertFalse(f.game().isGameOver());
        assertEquals(events.trace, List.of("priority:0", "move:Hand:Stack", "priority:1",
                "move:Stack:Battlefield", "resolved:false", "priority:0"));
        return new Outcome(OriginalCompositeChoice.observe(f.game()), List.copyOf(creatures), spent, spentSources, activated,
                List.copyOf(events.trace), MyRandom.getRandom().nextLong());
    }

    private static final class Recording implements IDecisionRecordCallback {
        final List<Map<String, Object>> requests = new ArrayList<>();
        final List<List<Integer>> answers = new ArrayList<>();
        Map<String, Object> pending;
        Map<String, Object> execution;
        @Override public void before(Map<String, Object> data) { assertNull(pending); pending = data; }
        @Override public void after(List<Integer> result) { requests.add(pending); answers.add(List.copyOf(result)); pending = null; }
        @Override public void executed(Map<String, Object> data) { assertNull(execution); execution = data; }
    }

    private static void close(ManaPaymentBoundaryTest.Fixture f) {
        for (Player player : f.game().getPlayers()) {
            if (player.getController() instanceof PlayerControllerRl rl) { rl.closeObservation(); }
            if (player.getController() instanceof PlayerControllerTeacher teacher) { teacher.closeObservation(); }
        }
    }

    @Test public void teacherAndReplayPreserveNativePaymentAnthemResolutionAndPriority() {
        Outcome baseline = run(fixture(new LobbyPlayerAi("first", null)));
        Recording recording = new Recording();
        var teacher = fixture(new LobbyPlayerTeacher("first", recording));
        var opponent = teacher.game().getPlayers().get(1);
        opponent.dangerouslySetController(new PlayerControllerTeacher(teacher.game(), opponent,
                new LobbyPlayerAi("second", null), recording));
        assertEquals(run(teacher), baseline, "Teacher changed native facts, traits, payment, events or RNG");
        assertNull(recording.pending);
        assertEquals(recording.requests.size(), 5);
        assertNotNull(recording.execution);
        int[] position = {0};
        int[] executions = {0};
        var callback = new IPlayerDecisionCallback() {
            @Override public List<Integer> choose(Map<String, Object> req) {
                assertEquals(req, recording.requests.get(position[0]));
                return recording.answers.get(position[0]++);
            }
            @Override public void executed(Map<String, Object> data) { assertEquals(data, recording.execution); executions[0]++; }
        };
        var replay = fixture(new LobbyPlayerRl("first", callback));
        opponent = replay.game().getPlayers().get(1);
        opponent.dangerouslySetController(new PlayerControllerRl(replay.game(), opponent, new LobbyPlayerAi("second", null), callback));
        assertNotSame(replay.spell(), teacher.spell());
        Outcome applied = run(replay);
        assertEquals(applied.facts(), baseline.facts());
        assertEquals(applied.creatures(), baseline.creatures());
        assertEquals(applied.spent(), baseline.spent());
        assertEquals(applied.spentSources(), baseline.spentSources());
        assertEquals(applied.activated(), baseline.activated());
        assertEquals(applied.events(), baseline.events());
        assertEquals(position[0], recording.requests.size());
        assertEquals(executions[0], 1);
        close(teacher); close(replay);
    }

    private static List<Integer> cast(Map<String, Object> request) {
        var items = (List<?>) ((Map<?, ?>) request.get("question")).get("items");
        for (int i = 1; i < items.size(); i++) {
            if ("cast_spell".equals(((Map<?, ?>) items.get(i)).get("type"))) { return List.of(i); }
        }
        throw new AssertionError("Missing Lyra spell option");
    }

    @Test public void cancellationCopiedAbilityAndStaleTimingStayDistinct() {
        for (String kind : List.of("cancel", "copy", "timing")) {
            List<Map<String, Object>> results = new ArrayList<>();
            var f = fixture(new LobbyPlayerRl("first", new IPlayerDecisionCallback() {
                @Override public List<Integer> choose(Map<String, Object> request) {
                    return "mana_payment".equals(((Map<?, ?>) request.get("question")).get("kind")) ? List.of(0, 0) : cast(request);
                }
                @Override public void executed(Map<String, Object> data) { results.add(data); }
            }));
            var controller = f.player().getController();
            var spell = controller.chooseSpellAbilityToPlay().get(0);
            if (kind.equals("cancel")) {
                assertFalse(controller.playChosenSpellAbility(spell));
                assertEquals(results.size(), 1);
                assertEquals(((Map<?, ?>) results.get(0).get("question")).get("onStack"), false);
                assertTrue(f.game().getCardState(spell.getHostCard()).isInZone(ZoneType.Hand));
            } else {
                if (kind.equals("timing")) { f.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_BEGIN, f.player(), false, 6); }
                RuntimeException failure = expectThrows(IllegalArgumentException.class,
                        () -> controller.playChosenSpellAbility(kind.equals("copy") ? spell.copy() : spell));
                assertSame(expectThrows(IllegalArgumentException.class, controller::chooseSpellAbilityToPlay), failure);
                assertTrue(results.isEmpty());
            }
            assertTrue(f.lands().stream().noneMatch(Card::isTapped));
            assertTrue(f.player().getManaPool().isEmpty());
            assertTrue(f.game().getStack().isEmpty());
            close(f);
        }
    }

    @Test public void otherKeywordsTriggersAndStaticShapesStillStopBeforeCallingTheClient() {
        for (String kind : List.of("Serra Angel", "Ajani's Pridemate", "static")) {
            var f = fixture(new LobbyPlayerRl("first", request -> { throw new AssertionError("Unexpected client request"); }));
            if (kind.equals("static")) {
                f.spell().getHostCard().getStaticAbilities().getFirst().getMapParams().put("AddPower", "2");
            } else { OriginalCompositeChoice.add(f.game(), f.player(), kind); }
            assertThrows(UnsupportedOperationException.class, f.player().getController()::chooseSpellAbilityToPlay);
            close(f);
        }
        var combat = AttackersTest.fixture(new LobbyPlayerRl("first", request -> { throw new AssertionError("Unexpected combat request"); }), false);
        Card lyra = OriginalCompositeChoice.add(combat.game(), combat.actor(), "Lyra Dawnbringer");
        combat.actor().getZone(ZoneType.Hand).remove(lyra);
        combat.actor().getZone(ZoneType.Battlefield).add(lyra);
        combat.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, combat.actor(), false, 6);
        assertThrows(UnsupportedOperationException.class, () -> combat.actor().getController().declareAttackers(combat.actor(), combat.game().getCombat()));
        ((PlayerControllerRl) combat.actor().getController()).closeObservation();
    }
}
