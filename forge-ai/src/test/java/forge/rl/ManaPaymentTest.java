package forge.rl;

import forge.ai.LobbyPlayerAi;
import forge.ai.AiCostDecision;
import forge.game.card.Card;
import forge.game.cost.PaymentDecision;
import forge.game.cost.CostPayment;
import forge.game.mana.Mana;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.testng.Assert.*;

/** The native CostPartMana entry, independent of priority and casting. */
public class ManaPaymentTest {
    @BeforeClass public void initialize() { OriginalCompositeChoice.initialize(Path.of("..")); }

    private static final class Recording implements IDecisionRecordCallback {
        Map<String, Object> request;
        List<Integer> answer;
        int requests;
        @Override public void before(Map<String, Object> value) { assertNull(request); request = value; requests++; }
        @Override public void after(List<Integer> value) { assertNull(answer); answer = List.copyOf(value); }
    }
    private record Result(boolean paid, List<Boolean> taps, List<String> pool, List<String> spent,
                          List<Integer> activations, List<String> debt, List<Object> facts, long rng) { }

    private static String unit(ManaPaymentBoundaryTest.Fixture f, Mana mana) {
        return f.lands().stream().filter(c -> c.getId() == mana.getSourceCard().getId())
                .map(c -> f.lands().indexOf(c) + ":" + mana.getColor()).findFirst().orElseThrow();
    }

    private static Result pay(ManaPaymentBoundaryTest.Fixture f) {
        boolean paid;
        if (f.player().getController().isAI()) {
            paid = new CostPayment(f.spell().getPayCosts(), f.spell())
                    .payComputerCosts(new AiCostDecision(f.player(), f.spell(), false));
        } else {
            // CostPayment copies the cost before calling payAsDecided; the independent payment uses that same entry.
            var part = f.spell().getPayCosts().copy().getCostMana();
            assertNotSame(part, f.spell().getPayCosts().getCostMana());
            paid = part.payAsDecided(f.player(), PaymentDecision.number(0), f.spell(), false);
        }
        var pool = new ArrayList<String>();
        f.player().getManaPool().forEach(mana -> pool.add(unit(f, mana)));
        var debt = new ManaCostBeingPaid(f.spell().getPayCosts().getTotalMana());
        f.spell().getPayingMana().forEach(mana -> debt.payMana(mana, f.player().getManaPool()));
        assertEquals(debt.isPaid(), paid);
        assertTrue(f.game().getStack().isEmpty(), "Payment must not cast the spell");
        assertTrue(f.spell().getHostCard().isInZone(ZoneType.Hand));
        return new Result(paid, f.lands().stream().map(Card::isTapped).toList(), pool,
                f.spell().getPayingMana().stream().map(mana -> unit(f, mana)).toList(),
                f.spell().getPayingManaAbilities().stream().map(sa -> f.lands().indexOf(sa.getHostCard())).toList(),
                debt.getUnpaidShards().stream().map(Object::toString).toList(),
                OriginalCompositeChoice.observe(f.game()), MyRandom.getRandom().nextLong());
    }

    private static void restrict(ManaPaymentBoundaryTest.Fixture f, boolean insufficient) {
        if (insufficient) {
            for (int i = 1; i < f.lands().size(); i++) { f.lands().get(i).setTapped(true); }
        }
    }

    @Test public void teacherPaymentIsNeutralAndReplaysFinalChoicesWithoutAi() {
        for (boolean floating : List.of(false, true)) {
            for (boolean insufficient : List.of(false, true)) {
                if (floating && insufficient) { continue; }
                var baseline = ManaPaymentBoundaryTest.fixture(new LobbyPlayerAi("first", null), floating);
                restrict(baseline, insufficient);
                Result expected = pay(baseline);
                Recording recording = new Recording();
                var teacher = ManaPaymentBoundaryTest.fixture(new LobbyPlayerTeacher("first", recording), floating);
                restrict(teacher, insufficient);
                Result recorded = pay(teacher);
                assertEquals(recorded, expected, "Teacher changed payment facts or RNG");
                assertEquals(recording.requests, 1);
                assertNotNull(recording.answer);
                assertEquals(recording.answer.get(0).intValue(), insufficient ? 0 : 1);
                int[] calls = {0};
                var replay = ManaPaymentBoundaryTest.fixture(new LobbyPlayerRl("first", req -> {
                    assertEquals(++calls[0], 1);
                    assertEquals(req, recording.request);
                    return recording.answer;
                }), floating);
                restrict(replay, insufficient);
                assertNotSame(replay.spell(), teacher.spell());
                Result applied = pay(replay);
                assertEquals(applied.paid(), recorded.paid());
                assertEquals(applied.taps(), recorded.taps());
                assertEquals(applied.pool(), recorded.pool());
                assertEquals(applied.spent(), recorded.spent());
                assertEquals(applied.activations(), recorded.activations());
                assertEquals(applied.debt(), recorded.debt());
                assertEquals(applied.facts(), recorded.facts());
                assertEquals(calls[0], 1);
                ((PlayerControllerTeacher) teacher.player().getController()).closeObservation();
                ((PlayerControllerRl) replay.player().getController()).closeObservation();
            }
        }
    }

    @Test public void clientChoosesSourcesSpendOrderAndCanDeclineWithoutInventedRefund() {
        for (List<Integer> answer : List.of(List.of(1, 2, 1, 0, 1, 0), List.of(0, 0), List.of(0, 1, 1), List.of(0, 1, 1, 1))) {
            var f = ManaPaymentBoundaryTest.fixture(new LobbyPlayerRl("first", req -> answer), false);
            Result result = pay(f);
            assertEquals(result.paid(), answer.get(0) == 1);
            assertEquals(result.activations().size(), answer.get(1).intValue());
            assertEquals(result.spent().size(), answer.size() - 2 - answer.get(1));
            assertEquals(f.player().getManaPool().totalMana(), answer.get(1) - result.spent().size());
            ((PlayerControllerRl) f.player().getController()).closeObservation();
        }
    }

    @Test public void invalidAnswersAndRecorderErrorsStopWithoutFabricatedResults() {
        for (List<Integer> invalid : List.of(List.<Integer>of(), List.of(1, 1, 99), List.of(1, 2, 0, 0, 0, 1),
                List.of(1, 0, 0, 1), List.of(1, 1, 2, 2), List.of(0, 2, 0, 1, 0, 1))) {
            var f = ManaPaymentBoundaryTest.fixture(new LobbyPlayerRl("first", request -> invalid), false);
            var before = OriginalCompositeChoice.observe(f.game());
            assertThrows(IllegalArgumentException.class, () -> pay(f));
            assertEquals(OriginalCompositeChoice.observe(f.game()), before);
            assertThrows(IllegalArgumentException.class, () -> pay(f));
            ((PlayerControllerRl) f.player().getController()).closeObservation();
        }
        var broken = ManaPaymentBoundaryTest.fixture(new LobbyPlayerTeacher("first", new IDecisionRecordCallback() {
            @Override public void before(Map<String, Object> request) { }
            @Override public void after(List<Integer> result) { throw new IllegalStateException("recorder failed"); }
        }), false);
        assertThrows(IllegalStateException.class, () -> pay(broken));
        assertEquals(broken.spell().getPayingMana().size(), 2);
        assertTrue(broken.lands().stream().anyMatch(Card::isTapped));
        assertThrows(IllegalStateException.class, () -> pay(broken));
        ((PlayerControllerTeacher) broken.player().getController()).closeObservation();
        var mandatory = ManaPaymentBoundaryTest.fixture(new LobbyPlayerRl("first", request -> {
            throw new AssertionError("Mandatory payment must fail before offering cancellation");
        }), false);
        mandatory.spell().getPayCosts().setMandatory(true);
        var before = OriginalCompositeChoice.observe(mandatory.game());
        assertThrows(UnsupportedOperationException.class, () -> pay(mandatory));
        assertEquals(OriginalCompositeChoice.observe(mandatory.game()), before);
        ((PlayerControllerRl) mandatory.player().getController()).closeObservation();
    }

    @Test public void equivalentManaSourceLimitationSurfacesBeforeWrongUnitIsRemoved() {
        for (List<Integer> answer : List.of(List.of(1, 2, 0, 1, 1, 0), List.of(0, 2, 0, 1, 1))) {
            var f = ManaPaymentBoundaryTest.fixture(new LobbyPlayerRl("first", req -> answer), false);
            assertThrows(UnsupportedOperationException.class, () -> pay(f));
            assertTrue(f.lands().get(0).isTapped());
            assertTrue(f.lands().get(1).isTapped());
            assertEquals(f.player().getManaPool().totalMana(), 2);
            assertTrue(f.spell().getPayingMana().isEmpty());
            assertEquals(f.spell().getPayingManaAbilities().size(), 2);
            var pool = new ArrayList<String>();
            f.player().getManaPool().forEach(mana -> pool.add(unit(f, mana)));
            assertEquals(pool, List.of("0:16", "1:16"));
            assertThrows(UnsupportedOperationException.class, () -> f.player().getController().chooseSpellAbilityToPlay());
            ((PlayerControllerRl) f.player().getController()).closeObservation();
        }
    }

    public static void main(String[] args) {
        OriginalCompositeChoice.initialize(Path.of(args[0]));
        var test = new ManaPaymentTest();
        test.teacherPaymentIsNeutralAndReplaysFinalChoicesWithoutAi();
        test.clientChoosesSourcesSpendOrderAndCanDeclineWithoutInventedRefund();
        test.invalidAnswersAndRecorderErrorsStopWithoutFabricatedResults();
        test.equivalentManaSourceLimitationSurfacesBeforeWrongUnitIsRemoved();
        System.out.println("PASS: native fixed payment teacher/client/replay, resources, result and errors verified");
    }
}
