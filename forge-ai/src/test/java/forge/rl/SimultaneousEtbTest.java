package forge.rl;

import forge.rl.decisions.TriggerRecorder;
import forge.rl.decisions.TriggerExecutor;

import forge.LobbyPlayer;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;
import forge.rl.decisions.TriggerDecision;
import forge.util.MyRandom;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.*;

import static org.testng.Assert.*;

public class SimultaneousEtbTest {
    @BeforeClass public void initialize() { OriginalCompositeChoice.initialize(Path.of("..")); }

    private static Card place(ManaPaymentBoundaryTest.Fixture f, String name) {
        Card card = OriginalCompositeChoice.add(f.game(), f.player(), name);
        f.player().getZone(ZoneType.Hand).remove(card);
        f.player().getZone(ZoneType.Battlefield).add(card);
        return card;
    }

    static ManaPaymentBoundaryTest.Fixture fixture(LobbyPlayer lobby, boolean mixed) {
        var f = HelpfulHunterTest.fixture(lobby);
        place(f, "Hinterland Sanctifier");
        if (mixed) { return f; }
        place(f, "Hinterland Sanctifier");
        f.player().getZone(ZoneType.Hand).remove(f.spell().getHostCard());
        var spell = OriginalCompositeChoice.add(f.game(), f.player(), "Grizzly Bears").getFirstSpellAbility();
        spell.setActivatingPlayer(f.player());
        return new ManaPaymentBoundaryTest.Fixture(f.game(), f.player(), spell, f.lands());
    }

    static void castAndResolve(ManaPaymentBoundaryTest.Fixture f) {
        var phase = f.game().getPhaseHandler();
        phase.onStackResolved();
        phase.mainLoopStep();
        assertSame(f.game().getStack().peekAbility(), f.spell());
        phase.mainLoopStep();
        assertTrue(f.game().getCardState(f.spell().getHostCard()).isInZone(ZoneType.Battlefield));
        assertTrue(f.game().getStack().isEmpty());
    }

    private static final class Probe extends PlayerControllerAi {
        final List<String> calls = new ArrayList<>();
        List<SpellAbility> before;
        List<SpellAbility> ordered;
        final List<SpellAbility> resolved = new ArrayList<>();
        Probe(Player player) { super(player.getGame(), player, new LobbyPlayerAi("first", null)); }
        @Override public void orderAndPlaySimultaneousSa(List<SpellAbility> abilities) {
            calls.add("prepare");
            before = List.copyOf(abilities);
            assertEquals(before.size(), 2);
            assertNotSame(before.get(0).getHostCard(), before.get(1).getHostCard());
            for (SpellAbility ability : before) {
                var wrapper = (WrappedAbility) ability;
                assertTrue(wrapper.isMandatory());
                assertNull(wrapper.getDecider());
                assertTrue(wrapper.getTargets().isEmpty());
                assertSame(wrapper.getHostCard().getTriggers().get(0), wrapper.getTrigger());
            }
            super.orderAndPlaySimultaneousSa(abilities);
            var stack = new ArrayList<SpellAbility>();
            getGame().getStack().forEach(entry -> stack.add(entry.getSpellAbility()));
            assertSame(stack.get(0), ordered.get(1));
            assertSame(stack.get(1), ordered.get(0));
        }
        @Override public List<SpellAbility> orderSimultaneousSa(List<SpellAbility> abilities) {
            calls.add("order");
            ordered = List.copyOf(super.orderSimultaneousSa(abilities));
            assertEquals(new HashSet<>(ordered), new HashSet<>(before));
            return ordered;
        }
        @Override public void playSpellAbilityNoStack(SpellAbility ability, boolean setupTargets) {
            calls.add("resolve");
            assertFalse(setupTargets);
            assertSame(ability, ((WrappedAbility) getGame().getStack().peekAbility()).getWrappedAbility());
            assertTrue(getGame().getStack().isResolving());
            resolved.add(ability);
            super.playSpellAbilityNoStack(ability, setupTargets);
        }
        @Override public boolean confirmTrigger(WrappedAbility ability) { throw new AssertionError("Unexpected confirmation"); }
        @Override public boolean playTrigger(Card host, WrappedAbility ability, boolean mandatory) { throw new AssertionError("Unexpected static trigger"); }
    }

    private record Outcome(List<Object> facts, List<List<Integer>> intermediate, long rng) { }

    private record Run(Outcome result, List<String> spent, List<Integer> sources,
                       List<Integer> activated, List<String> events) { }

    private static Run run(ManaPaymentBoundaryTest.Fixture f, boolean mixed) {
        var events = new CastingPriorityTest.Events(f);
        f.game().subscribeToEvents(events);
        castAndResolve(f);
        var spent = f.spell().getPayingMana().stream().map(Object::toString).toList();
        var ids = f.lands().stream().map(Card::getId).toList();
        var sources = f.spell().getPayingMana().stream().map(mana -> ids.indexOf(mana.getSourceCard().getId())).toList();
        var activated = f.spell().getPayingManaAbilities().stream().map(sa -> f.lands().indexOf(sa.getHostCard())).toList();
        var result = finish(f, mixed);
        assertTrue(f.lands().stream().allMatch(Card::isTapped));
        assertTrue(f.player().getManaPool().isEmpty());
        return new Run(result, spent, sources, activated, List.copyOf(events.trace));
    }

    private static Outcome finish(ManaPaymentBoundaryTest.Fixture f, boolean mixed) {
        var phase = f.game().getPhaseHandler();
        phase.mainLoopStep();
        assertEquals(f.game().getStack().size(), 2);
        var intermediate = new ArrayList<List<Integer>>();
        for (int i = 0; i < 2; i++) {
            phase.mainLoopStep();
            assertEquals(f.game().getStack().size(), 1 - i);
            intermediate.add(List.of(f.player().getLife(), f.player().getCardsIn(ZoneType.Hand).size()));
            phase.mainLoopStep();
        }
        assertSame(phase.getPriorityPlayer(), f.game().getPlayers().get(1));
        assertEquals(f.player().getLife(), mixed ? 21 : 22);
        assertEquals(f.player().getCardsIn(ZoneType.Hand).size(), mixed ? 1 : 0);
        assertFalse(f.game().isGameOver());
        return new Outcome(OriginalCompositeChoice.observe(f.game()), intermediate, MyRandom.getRandom().nextLong());
    }

    @Test public void nativeBoundaryExposesBothExactWrappersAndTheirResolutionOrder() {
        for (boolean mixed : List.of(false, true)) {
            var baseline = fixture(new LobbyPlayerAi("first", null), mixed);
            castAndResolve(baseline);
            Outcome expected = finish(baseline, mixed);
            var f = fixture(new LobbyPlayerAi("first", null), mixed);
            var probe = new Probe(f.player());
            f.player().dangerouslySetController(probe);
            castAndResolve(f);
            assertEquals(finish(f, mixed), expected);
            assertEquals(probe.calls, List.of("prepare", "order", "resolve", "resolve"));
            assertSame(probe.resolved.get(0), ((WrappedAbility) probe.ordered.get(1)).getWrappedAbility());
            assertSame(probe.resolved.get(1), ((WrappedAbility) probe.ordered.get(0)).getWrappedAbility());
        }
    }

    @Test public void teacherPreservesFactsOrderedPaymentEventsAndRngAndClientReplaysExactly() {
        for (boolean mixed : List.of(false, true)) {
            Run baseline = run(fixture(new LobbyPlayerAi("first", null), mixed), mixed);
            var recording = new HelpfulHunterTest.Recording();
            var teacher = fixture(new LobbyPlayerTeacher("first", recording), mixed);
            HelpfulHunterTest.opponent(teacher, recording);
            assertEquals(run(teacher, mixed), baseline);
            assertNull(recording.pending);
            var kinds = recording.requests.stream().map(request -> ((Map<?, ?>) request.get("options")).get("kind")).toList();
            assertEquals(Collections.frequency(kinds, "trigger_prepare"), 1);
            int preparation = kinds.indexOf("trigger_prepare");
            assertEquals(new HashSet<>(recording.answers.get(preparation)), Set.of(0, 1));
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
            var replay = fixture(new LobbyPlayerRl("first", callback), mixed);
            Player opponent = replay.game().getPlayers().get(1);
            opponent.dangerouslySetController(new PlayerControllerRl(replay.game(), opponent, new LobbyPlayerAi("second", null), callback));
            Run applied = run(replay, mixed);
            assertEquals(applied.result().facts(), baseline.result().facts());
            assertEquals(applied.result().intermediate(), baseline.result().intermediate());
            assertEquals(applied.spent(), baseline.spent());
            assertEquals(applied.sources(), baseline.sources());
            assertEquals(applied.activated(), baseline.activated());
            assertEquals(applied.events(), baseline.events());
            assertEquals(position[0], recording.requests.size());
            assertEquals(executions[0], 1);
            HelpfulHunterTest.close(teacher); HelpfulHunterTest.close(replay);
        }
    }

    private static List<SpellAbility> prepared(ManaPaymentBoundaryTest.Fixture f) {
        castAndResolve(f);
        f.game().getPhaseHandler().mainLoopStep();
        List<SpellAbility> wrappers = new ArrayList<>();
        f.game().getStack().forEach(entry -> wrappers.add(entry.getSpellAbility()));
        assertEquals(wrappers.size(), 2);
        f.game().getStack().clear();
        return wrappers;
    }

    @Test public void bothOrdersPreserveTheExactWrappersAndExistingStackTail() {
        for (var answer : List.of(List.of(0, 1), List.of(1, 0))) {
            var f = fixture(new LobbyPlayerAi("first", null), true);
            var wrappers = prepared(f);
            var tail = new WrappedAbility(wrappers.get(0).getTrigger(),
                    ((WrappedAbility) wrappers.get(0)).getWrappedAbility().copy(), null);
            f.game().getStack().add(tail);
            var view = new StateReader(f.player(), 0L);
            var request = new TriggerDecision(f.player(), view, wrappers);
            assertThrows(IllegalStateException.class, () -> new TriggerRecorder(request).encode());
            assertThrows(IllegalArgumentException.class, () -> request.requireBoundOrder(List.of(wrappers.get(1), wrappers.get(0))));
            new TriggerExecutor(request, answer).apply();
            assertEquals(new TriggerRecorder(request).encode(), answer);
            var stack = new ArrayList<SpellAbility>();
            f.game().getStack().forEach(entry -> stack.add(entry.getSpellAbility()));
            assertSame(stack.get(0), wrappers.get(answer.get(1)));
            assertSame(stack.get(1), wrappers.get(answer.get(0)));
            assertSame(stack.get(2), tail);
            assertThrows(IllegalArgumentException.class, () -> new TriggerExecutor(request, answer).apply());
            view.close();
        }
    }

    @Test public void invalidAnswersCopiesForeignSourcesStaleStateAndFailuresStopBeforeInsertion() {
        for (String kind : List.of("duplicate", "missing", "index", "copy", "foreign", "stale", "stack", "callback", "larger", "optional")) {
            var f = fixture(new LobbyPlayerAi("first", null), true);
            var wrappers = prepared(f);
            if (kind.equals("copy")) {
                var original = (WrappedAbility) wrappers.get(1);
                wrappers.set(1, new WrappedAbility(original.getTrigger().copy(original.getHostCard(), true), original.getWrappedAbility().copy(), null));
            }
            if (kind.equals("foreign")) { wrappers.get(1).setActivatingPlayer(f.game().getPlayers().get(1)); }
            if (kind.equals("optional")) { wrappers.get(1).setOptionalTrigger(true); }
            if (kind.equals("larger")) { wrappers.add(wrappers.get(0)); }
            var callbackFailure = new IllegalStateException("ETB callback failed");
            var controller = new PlayerControllerRl(f.game(), f.player(), new LobbyPlayerAi("first", null), request -> {
                if (kind.equals("callback")) { throw callbackFailure; }
                if (kind.equals("stale")) {
                    Card source = wrappers.get(1).getHostCard();
                    f.player().getZone(ZoneType.Battlefield).remove(source);
                    f.player().getZone(ZoneType.Exile).add(source);
                }
                if (kind.equals("stack")) { f.game().getStack().add(wrappers.get(0)); }
                return switch (kind) {
                    case "duplicate" -> List.of(0, 0);
                    case "missing" -> List.of(0);
                    case "index" -> List.of(0, 2);
                    case "stale", "stack" -> List.of(0, 1);
                    default -> throw new AssertionError("Unsupported ETB reached the client");
                };
            });
            RuntimeException failure = expectThrows(RuntimeException.class, () -> controller.orderAndPlaySimultaneousSa(wrappers));
            if (kind.equals("callback")) { assertSame(failure, callbackFailure); }
            assertSame(expectThrows(RuntimeException.class, controller::chooseSpellAbilityToPlay), failure);
            assertEquals(f.game().getStack().size(), kind.equals("stack") ? 1 : 0);
            assertEquals(f.player().getLife(), 20);
            assertEquals(f.player().getCardsIn(ZoneType.Library).size(), 8);
            controller.closeObservation();
        }
    }

    @Test public void automaticFixedAmountsReusePreparationAndResolutionWithoutAnotherDecision() {
        for (var api : List.of(ApiType.Draw, ApiType.GainLife)) {
            var f = fixture(new LobbyPlayerAi("first", null), true);
            var wrappers = prepared(f);
            var wrapper = wrappers.stream().filter(sa -> sa.getApi() == api).findFirst().orElseThrow();
            var inner = ((WrappedAbility) wrapper).getWrappedAbility();
            inner.getMapParams().put(api == ApiType.Draw ? "NumCards" : "LifeAmount", "2");
            inner.getMapParams().put("Defined", "You");
            var view = new StateReader(f.player(), 0L);
            var request = new TriggerDecision(f.player(), view, List.of(wrapper));
            new TriggerExecutor(request, List.of(0)).apply();
            assertEquals(new TriggerRecorder(request).encode(), List.of(0));
            var controller = new PlayerControllerRl(f.game(), f.player(), new LobbyPlayerAi("first", null), data -> {
                throw new AssertionError("Automatic effect requested another decision");
            });
            f.player().dangerouslySetController(controller);
            f.game().getStack().resolveStack();
            assertTrue(f.game().getStack().isEmpty());
            assertEquals(f.player().getLife(), api == ApiType.Draw ? 20 : 22);
            assertEquals(f.player().getCardsIn(ZoneType.Hand).size(), api == ApiType.Draw ? 2 : 0);
            controller.closeObservation(); view.close();
        }
    }

    @Test public void conditionalOptionalChainedOrChosenEffectsStayOutsideTheContract() {
        for (String kind : List.of("origin", "condition", "optional", "variable", "recipient", "choice", "chain")) {
            var f = fixture(new LobbyPlayerAi("first", null), true);
            var wrappers = prepared(f);
            var wrapper = (WrappedAbility) wrappers.get(0);
            switch (kind) {
                case "origin" -> wrapper.getTrigger().getMapParams().put("Origin", "Graveyard");
                case "condition" -> wrapper.getTrigger().getMapParams().put("IsPresent", "Creature.YouCtrl");
                case "optional" -> wrapper.getTrigger().getMapParams().put("OptionalDecider", "You");
                case "variable" -> wrapper.getWrappedAbility().getMapParams().put(
                        wrapper.getApi() == ApiType.Draw ? "NumCards" : "LifeAmount", "X");
                case "recipient" -> wrapper.getWrappedAbility().getMapParams().put("Defined", "Opponent");
                case "choice" -> wrapper.getWrappedAbility().getMapParams().put("OptionalDecider", "You");
                case "chain" -> wrapper.setSubAbility(new forge.game.spellability.AbilitySub(ApiType.Draw,
                        wrapper.getHostCard(), null, Map.of("DB", "Draw")));
            }
            var controller = new PlayerControllerRl(f.game(), f.player(), new LobbyPlayerAi("first", null), data -> {
                throw new AssertionError("Unsupported effect reached the client");
            });
            assertThrows(UnsupportedOperationException.class, () -> controller.orderAndPlaySimultaneousSa(wrappers));
            assertTrue(f.game().getStack().isEmpty());
            controller.closeObservation();
        }
    }
}
