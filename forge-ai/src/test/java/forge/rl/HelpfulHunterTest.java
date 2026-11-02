package forge.rl;

import forge.rl.decisions.TriggerRecorder;

import forge.LobbyPlayer;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;
import forge.rl.decisions.TriggerDecision;
import forge.rl.decisions.PriorityDecision;
import forge.util.MyRandom;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.*;

import static org.testng.Assert.*;

public class HelpfulHunterTest {
    @BeforeClass public void initialize() { OriginalCompositeChoice.initialize(Path.of("..")); }

    private static Card place(ManaPaymentBoundaryTest.Fixture f, String name, ZoneType zone) {
        Card card = OriginalCompositeChoice.add(f.game(), f.player(), name);
        f.player().getZone(ZoneType.Hand).remove(card);
        f.player().getZone(zone).add(card);
        return card;
    }

    static ManaPaymentBoundaryTest.Fixture fixture(LobbyPlayer lobby) {
        var f = ManaPaymentBoundaryTest.fixture(lobby, false);
        f.player().getZone(ZoneType.Hand).remove(f.spell().getHostCard());
        f.lands().forEach(card -> f.player().getZone(ZoneType.Battlefield).remove(card));
        List<Card> lands = List.of(place(f, "Forest", ZoneType.Battlefield), place(f, "Plains", ZoneType.Battlefield));
        var spell = OriginalCompositeChoice.add(f.game(), f.player(), "Helpful Hunter").getFirstSpellAbility();
        spell.setActivatingPlayer(f.player());
        place(f, "Island", ZoneType.Library);
        for (int i = 0; i < 7; i++) { place(f, "Mountain", ZoneType.Library); }
        f.player().setLandsPlayedThisTurn(1);
        Player opponent = f.game().getPlayers().get(1);
        opponent.dangerouslySetController(new PlayerControllerAi(f.game(), opponent, new LobbyPlayerAi("second", null)));
        f.game().getPhaseHandler().devModeSet(PhaseType.MAIN2, f.player(), false, 6);
        return new ManaPaymentBoundaryTest.Fixture(f.game(), f.player(), spell, lands);
    }

    private record Outcome(List<Object> facts, List<String> spent, List<Integer> spentSources,
                           List<Integer> activated, List<String> events, long rng) { }

    private static Outcome run(ManaPaymentBoundaryTest.Fixture f) {
        var phase = f.game().getPhaseHandler();
        var events = new CastingPriorityTest.Events(f);
        f.game().subscribeToEvents(events);
        phase.onStackResolved();
        phase.mainLoopStep();
        assertSame(f.game().getStack().peekAbility(), f.spell());
        var spent = f.spell().getPayingMana().stream().map(Object::toString).toList();
        assertEquals(new HashSet<>(spent), Set.of("G", "W"));
        var sourceIds = f.lands().stream().map(Card::getId).toList();
        var sources = f.spell().getPayingMana().stream().map(mana -> sourceIds.indexOf(mana.getSourceCard().getId())).toList();
        var activated = f.spell().getPayingManaAbilities().stream().map(sa -> f.lands().indexOf(sa.getHostCard())).toList();
        assertTrue(f.lands().stream().allMatch(Card::isTapped));
        assertTrue(f.player().getManaPool().isEmpty());
        phase.mainLoopStep();
        Card hunter = f.game().getCardState(f.spell().getHostCard());
        assertTrue(hunter.isInZone(ZoneType.Battlefield));
        assertEquals(hunter.getNetPower(), 1);
        assertEquals(hunter.getNetToughness(), 1);
        assertTrue(hunter.hasSickness());
        assertTrue(f.player().getCardsIn(ZoneType.Hand).isEmpty());
        // Native preparation runs before the next priority request.
        phase.mainLoopStep();
        assertEquals(f.game().getStack().size(), 1);
        assertTrue(f.game().getStack().peekAbility() instanceof WrappedAbility);
        phase.mainLoopStep();
        assertTrue(f.game().getStack().isEmpty());
        assertEquals(f.player().getCardsIn(ZoneType.Hand).stream().map(Card::getName).toList(), List.of("Island"));
        assertEquals(f.player().getCardsIn(ZoneType.Library).stream().map(Card::getName).toList(), Collections.nCopies(7, "Mountain"));
        phase.mainLoopStep();
        assertSame(phase.getPriorityPlayer(), f.game().getPlayers().get(1));
        assertEquals(phase.getPhase(), PhaseType.MAIN2);
        assertFalse(f.game().isGameOver());
        return new Outcome(OriginalCompositeChoice.observe(f.game()), spent, sources, activated,
                List.copyOf(events.trace), MyRandom.getRandom().nextLong());
    }

    private static final class Probe extends PlayerControllerAi {
        final List<String> calls = new ArrayList<>();
        WrappedAbility wrapper;
        Probe(Player player) { super(player.getGame(), player, new LobbyPlayerAi("first", null)); }
        @Override public void orderAndPlaySimultaneousSa(List<SpellAbility> abilities) {
            calls.add("prepare");
            assertEquals(abilities.size(), 1);
            wrapper = (WrappedAbility) abilities.get(0);
            assertNull(wrapper.getDecider());
            assertTrue(wrapper.getTargets().isEmpty());
            assertEquals(wrapper.getTrigger().getMapParams(), Map.of("Mode", "ChangesZone", "Origin", "Any",
                    "Destination", "Battlefield", "ValidCard", "Card.Self", "Execute", "TrigDraw",
                    "TriggerDescription", "When this creature enters, draw a card."));
            assertEquals(wrapper.getWrappedAbility().getMapParams(), Map.of("DB", "Draw"));
            super.orderAndPlaySimultaneousSa(abilities);
            assertSame(getGame().getStack().peekAbility(), wrapper);
        }
        @Override public List<SpellAbility> orderSimultaneousSa(List<SpellAbility> abilities) {
            calls.add("order");
            var result = super.orderSimultaneousSa(abilities);
            assertSame(result.get(0), wrapper);
            return result;
        }
        @Override public void playSpellAbilityNoStack(SpellAbility ability, boolean setupTargets) {
            calls.add("resolve");
            assertFalse(setupTargets);
            assertSame(ability, wrapper.getWrappedAbility());
            assertSame(getGame().getStack().peekAbility(), wrapper);
            assertTrue(getGame().getStack().isResolving());
            super.playSpellAbilityNoStack(ability, setupTargets);
        }
        @Override public boolean confirmTrigger(WrappedAbility ability) { throw new AssertionError("Unexpected confirmation"); }
        @Override public boolean playTrigger(Card host, WrappedAbility ability, boolean mandatory) { throw new AssertionError("Unexpected static trigger"); }
    }

    @Test public void publicBoundaryExposesTheExactPreparedAndResolvingTrigger() {
        var baseline = run(fixture(new LobbyPlayerAi("first", null)));
        var f = fixture(new LobbyPlayerAi("first", null));
        var view = new StateReader(f.player(), 0L);
        var trigger = f.spell().getHostCard().getTriggers().get(0);
        var originalAbility = trigger.getOverridingAbility();
        new PriorityDecision(f.player(), view);
        assertSame(trigger.getOverridingAbility(), originalAbility);
        view.close();
        var probe = new Probe(f.player());
        f.player().dangerouslySetController(probe);
        assertEquals(run(f), baseline);
        assertEquals(probe.calls, List.of("prepare", "order", "resolve"));
    }

    static final class Recording implements IDecisionRecordCallback {
        final List<Map<String, Object>> requests = new ArrayList<>();
        final List<List<Integer>> answers = new ArrayList<>();
        Map<String, Object> pending;
        Map<String, Object> execution;
        @Override public void before(Map<String, Object> request) { assertNull(pending); pending = request; }
        @Override public void after(List<Integer> answer) {
            requests.add(pending); answers.add(List.copyOf(answer)); pending = null;
        }
        @Override public void executed(Map<String, Object> result) { assertNull(execution); execution = result; }
    }

    static void opponent(ManaPaymentBoundaryTest.Fixture f, IDecisionRecordCallback callback) {
        Player opponent = f.game().getPlayers().get(1);
        opponent.dangerouslySetController(new PlayerControllerTeacher(f.game(), opponent, new LobbyPlayerAi("second", null), callback));
    }

    static void close(ManaPaymentBoundaryTest.Fixture f) {
        for (Player player : f.game().getPlayers()) {
            if (player.getController() instanceof PlayerControllerRl rl) { rl.closeObservation(); }
            if (player.getController() instanceof PlayerControllerTeacher teacher) { teacher.closeObservation(); }
        }
    }

    @Test public void teacherAndClientPreservePaymentPreparationDrawAndNewPriority() {
        Outcome baseline = run(fixture(new LobbyPlayerAi("first", null)));
        Recording recording = new Recording();
        var teacher = fixture(new LobbyPlayerTeacher("first", recording));
        opponent(teacher, recording);
        assertEquals(run(teacher), baseline, "Teacher changed native facts, payment, events or RNG");
        assertNull(recording.pending);
        var kinds = recording.requests.stream().map(r -> ((Map<?, ?>) r.get("options")).get("kind")).toList();
        assertEquals(kinds, List.of("priority", "mana_payment", "priority", "priority", "trigger_prepare", "priority", "priority", "priority"));
        assertEquals(recording.answers.get(4), List.of(0));
        assertNotNull(recording.execution);
        int[] position = {0};
        int[] executions = {0};
        var callback = new IPlayerDecisionCallback() {
            @Override public List<Integer> choose(Map<String, Object> request) {
                assertEquals(request, recording.requests.get(position[0]));
                return recording.answers.get(position[0]++);
            }
            @Override public void executed(Map<String, Object> result) {
                assertEquals(result, recording.execution); executions[0]++;
            }
        };
        var replay = fixture(new LobbyPlayerRl("first", callback));
        Player opponent = replay.game().getPlayers().get(1);
        opponent.dangerouslySetController(new PlayerControllerRl(replay.game(), opponent, new LobbyPlayerAi("second", null), callback));
        Outcome applied = run(replay);
        assertEquals(applied.facts(), baseline.facts());
        assertEquals(applied.spent(), baseline.spent());
        assertEquals(applied.spentSources(), baseline.spentSources());
        assertEquals(applied.activated(), baseline.activated());
        assertEquals(applied.events(), baseline.events());
        assertEquals(position[0], recording.requests.size());
        assertEquals(executions[0], 1);
        close(teacher); close(replay);
    }

    @Test public void copiedInnerAbilityAndForeignResolutionAreRejectedBeforeDrawing() {
        for (String kind : List.of("copy", "setup", "outside")) {
            var f = fixture(new LobbyPlayerAi("first", null));
            f.game().getPhaseHandler().onStackResolved();
            f.game().getPhaseHandler().mainLoopStep();
            f.game().getPhaseHandler().mainLoopStep();
            f.game().getPhaseHandler().mainLoopStep();
            var wrapper = (WrappedAbility) f.game().getStack().peekAbility();
            f.game().getStack().setResolving(!kind.equals("outside"));
            var controller = new PlayerControllerRl(f.game(), f.player(), new LobbyPlayerAi("first", null),
                    request -> { throw new AssertionError("Automatic draw must not ask the client"); });
            assertThrows(RuntimeException.class, () -> controller.playSpellAbilityNoStack(
                    kind.equals("copy") ? wrapper.getWrappedAbility().copy() : wrapper.getWrappedAbility(), kind.equals("setup")));
            assertEquals(f.player().getCardsIn(ZoneType.Library).size(), 8);
            assertTrue(f.player().getCardsIn(ZoneType.Hand).isEmpty());
            controller.closeObservation();
        }
        var combat = AttackersTest.fixture(new LobbyPlayerRl("first", request -> {
            throw new AssertionError("Unexpected combat question");
        }), false);
        Card hunter = OriginalCompositeChoice.add(combat.game(), combat.actor(), "Helpful Hunter");
        combat.actor().getZone(ZoneType.Hand).remove(hunter);
        combat.actor().getZone(ZoneType.Battlefield).add(hunter);
        combat.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, combat.actor(), false, 6);
        assertThrows(UnsupportedOperationException.class,
                () -> combat.actor().getController().declareAttackers(combat.actor(), combat.game().getCombat()));
        ((PlayerControllerRl) combat.actor().getController()).closeObservation();
    }

    @Test public void preparationKeepsExactWrapperAndRejectsStaleSource() {
        var f = fixture(new LobbyPlayerAi("first", null));
        f.game().getPhaseHandler().onStackResolved();
        for (int i = 0; i < 3; i++) { f.game().getPhaseHandler().mainLoopStep(); }
        var wrapper = (WrappedAbility) f.game().getStack().peekAbility();
        f.game().getStack().clear();
        var view = new StateReader(f.player(), 0L);
        var request = new TriggerDecision(f.player(), view, List.of(wrapper));
        var other = new WrappedAbility(wrapper.getTrigger(), wrapper.getWrappedAbility().copy(), null);
        assertThrows(IllegalArgumentException.class, () -> request.requireBoundOrder(List.of(other)));
        assertThrows(IllegalStateException.class, () -> new TriggerRecorder(request).encode());
        Card source = wrapper.getHostCard();
        var controller = new PlayerControllerRl(f.game(), f.player(), new LobbyPlayerAi("first", null), data -> {
            f.player().getZone(ZoneType.Battlefield).remove(source);
            f.player().getZone(ZoneType.Exile).add(source);
            return List.of(0);
        });
        assertThrows(IllegalArgumentException.class, () -> controller.orderAndPlaySimultaneousSa(List.of(wrapper)));
        assertTrue(f.game().getStack().isEmpty());
        assertEquals(f.player().getCardsIn(ZoneType.Library).size(), 8);
        controller.closeObservation();
        view.close();
    }

    @Test public void unsupportedShapesSimultaneousTriggersAndPreparationErrorsStayExplicit() {
        for (String kind : List.of("optional", "optional_draw", "simultaneous", "bad_answer", "callback")) {
            var f = fixture(new LobbyPlayerAi("first", null));
            f.game().getPhaseHandler().onStackResolved();
            f.game().getPhaseHandler().mainLoopStep();
            f.game().getPhaseHandler().mainLoopStep();
            f.game().getPhaseHandler().mainLoopStep();
            var wrapper = (WrappedAbility) f.game().getStack().peekAbility();
            f.game().getStack().clear();
            if (kind.equals("optional")) { wrapper.setOptionalTrigger(true); }
            if (kind.equals("optional_draw")) { wrapper.getWrappedAbility().getMapParams().put("Upto", "True"); }
            RuntimeException callbackFailure = new IllegalStateException("client preparation failed");
            var controller = new PlayerControllerRl(f.game(), f.player(), new LobbyPlayerAi("first", null), request -> {
                if (kind.equals("callback")) { throw callbackFailure; }
                assertEquals(kind, "bad_answer", "Unsupported shape reached the client");
                return List.of();
            });
            List<SpellAbility> abilities = kind.equals("simultaneous") ? List.of(wrapper, wrapper.copy()) : List.of(wrapper);
            RuntimeException failure = expectThrows(RuntimeException.class, () -> controller.orderAndPlaySimultaneousSa(abilities));
            if (kind.equals("callback")) { assertSame(failure, callbackFailure); }
            assertSame(expectThrows(RuntimeException.class, controller::chooseSpellAbilityToPlay), failure);
            assertTrue(f.game().getStack().isEmpty());
            assertEquals(f.player().getCardsIn(ZoneType.Library).size(), 8);
            controller.closeObservation();
        }
    }
}
