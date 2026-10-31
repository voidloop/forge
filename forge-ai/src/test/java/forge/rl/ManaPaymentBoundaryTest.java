package forge.rl;

import forge.LobbyPlayer;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.card.mana.ManaCost;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostTap;
import forge.game.cost.PaymentDecision;
import forge.game.mana.Mana;
import forge.game.mana.ManaConversionMatrix;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.deck.Deck;
import forge.util.MyRandom;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

import static org.testng.Assert.*;

/** Public payment evidence only; no production protocol or general casting implementation. */
public class ManaPaymentBoundaryTest {
    record Fixture(Game game, Player player, SpellAbility spell, List<Card> lands) { }
    record Activation(int source, int ability) { }
    record Unit(int source, byte color) { }
    record Payment(List<Activation> activations, List<Unit> spent) { }
    record Resources(List<Boolean> tapped, List<Unit> pool, List<Unit> spent, List<String> unpaid) { }
    record Outcome(List<Object> before, List<Object> prepared, List<Object> after,
                   Payment payment, Resources resources, long rng) { }

    @BeforeClass public void initialize() { OriginalCompositeChoice.initialize(Path.of("..")); }

    private static final class RecordingController extends PlayerControllerAi {
        List<Card> bindings;
        SpellAbility root;
        Payment payment;
        List<Object> before;
        int executions;
        int payments;
        int poolQuestions;

        RecordingController(Game game, Player player, LobbyPlayerAi lobby) { super(game, player, lobby); }

        @Override public boolean playChosenSpellAbility(SpellAbility ability) {
            assertEquals(++executions, 1);
            root = ability;
            bindings = List.copyOf(player.getCardsIn(ZoneType.Battlefield));
            before = OriginalCompositeChoice.observe(getGame());
            assertTrue(ability.getPayingMana().isEmpty());
            assertTrue(ability.getPayingManaAbilities().isEmpty());
            boolean reported = super.playChosenSpellAbility(ability);
            assertSame(getGame().getStack().peekAbility(), ability, "A reported execution is not enough");
            payment = capture(ability, bindings);
            return reported;
        }

        @Override public boolean payManaCost(ManaCost cost, CostPartMana part, SpellAbility ability,
                String prompt, ManaConversionMatrix matrix, boolean effect) {
            boolean paid = super.payManaCost(cost, part, ability, prompt, matrix, effect);
            if (ability == root) {
                payments++;
                assertTrue(paid, "The original executor must confirm payment");
                assertEquals(cost, root.getPayCosts().getTotalMana());
            }
            return paid;
        }

        @Override public Mana chooseManaFromPool(List<Mana> choices) {
            poolQuestions++;
            return super.chooseManaFromPool(choices);
        }
    }

    private static Fixture fixture(LobbyPlayer lobby, boolean floating) {
        MyRandom.setRandom(new Random(0));
        var seats = List.of(new RegisteredPlayer(new Deck("first")).setPlayer(lobby),
                new RegisteredPlayer(new Deck("second")).setPlayer(new LobbyPlayerRl("second", request -> {
                    throw new AssertionError("Unexpected opponent question");
                })));
        Game game = new Match(new GameRules(GameType.Constructed), seats, "payment boundary").createGame();
        game.getRegisteredPlayers().forEach(player -> player.setLife(20, null));
        game.setAge(GameStage.Play);
        Player player = game.getRegisteredPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, player, false, 6);
        game.getPhaseHandler().setPriority(player);
        List<Card> lands = new ArrayList<>();
        for (String name : List.of("Forest", "Forest", "Island", "Mountain")) {
            Card land = OriginalCompositeChoice.add(game, player, name);
            player.getZone(ZoneType.Hand).remove(land);
            player.getZone(ZoneType.Battlefield).add(land);
            assertEquals(land.getManaAbilities().size(), 1);
            lands.add(land);
        }
        SpellAbility spell = OriginalCompositeChoice.add(game, player, "Grizzly Bears").getFirstSpellAbility();
        spell.setActivatingPlayer(player);
        if (floating) {
            activate(player, lands.get(2).getManaAbilities().get(0));
            activate(player, lands.get(3).getManaAbilities().get(0));
            assertEquals(player.getManaPool().totalMana(), 2);
        }
        return new Fixture(game, player, spell, List.copyOf(lands));
    }

    private static void activate(Player player, SpellAbility ability) {
        ability.setActivatingPlayer(player);
        assertTrue(ability.canPlay());
        assertEquals(ability.getPayCosts().getCostParts().size(), 1);
        CostTap tap = (CostTap) ability.getPayCosts().getCostParts().get(0);
        assertTrue(tap.canPay(ability, player, false));
        assertTrue(tap.payAsDecided(player, PaymentDecision.number(1), ability, false));
        player.getGame().getStack().addAndUnfreeze(ability);
        assertTrue(ability.getHostCard().isTapped());
    }

    private static int source(List<Card> bindings, Card card) {
        // Mana retains LKI copies, so bind by native identity before detaching to a fixture slot.
        for (int i = 0; i < bindings.size(); i++) {
            if (bindings.get(i).getId() == card.getId()) { return i; }
        }
        throw new AssertionError("Unbound payment source");
    }

    private static Unit unit(List<Card> bindings, Mana mana) {
        return new Unit(source(bindings, mana.getSourceCard()), mana.getColor());
    }

    private static Payment capture(SpellAbility spell, List<Card> bindings) {
        return new Payment(spell.getPayingManaAbilities().stream().map(ability -> {
            int slot = source(bindings, ability.getHostCard());
            int index = bindings.get(slot).getSpellAbilities().indexOf(ability);
            assertTrue(index >= 0, "Unbound native ability variant");
            return new Activation(slot, index);
        }).toList(), spell.getPayingMana().stream().map(mana -> unit(bindings, mana)).toList());
    }

    private static Resources resources(Fixture fixture) {
        List<Unit> pool = new ArrayList<>();
        fixture.player().getManaPool().forEach(mana -> pool.add(unit(fixture.lands(), mana)));
        ManaCostBeingPaid debt = new ManaCostBeingPaid(fixture.spell().getPayCosts().getTotalMana());
        for (Mana mana : fixture.spell().getPayingMana()) { debt.payMana(mana, fixture.player().getManaPool()); }
        assertTrue(debt.isPaid(), "The actual spent units must cover both colored and generic debt");
        return new Resources(fixture.lands().stream().map(Card::isTapped).toList(), List.copyOf(pool),
                capture(fixture.spell(), fixture.lands()).spent(),
                debt.getUnpaidShards().stream().map(Object::toString).toList());
    }

    private static Outcome execute(Fixture fixture) {
        var before = OriginalCompositeChoice.observe(fixture.game());
        var controller = fixture.player().getController();
        var chosen = controller.chooseSpellAbilityToPlay();
        assertNotNull(chosen);
        assertEquals(chosen.size(), 1);
        assertSame(chosen.get(0), fixture.spell());
        var prepared = OriginalCompositeChoice.observe(fixture.game());
        assertTrue(controller.playChosenSpellAbility(chosen.get(0)));
        assertSame(fixture.game().getStack().peekAbility(), fixture.spell());
        assertFalse(fixture.game().isGameOver());
        return new Outcome(before, prepared, OriginalCompositeChoice.observe(fixture.game()),
                capture(fixture.spell(), fixture.lands()), resources(fixture), MyRandom.getRandom().nextLong());
    }

    private static ManaCostBeingPaid replayPayment(Fixture fixture, Payment payment) {
        // shortcut: basic lands producing one mana each, no triggers/conversion; prove other costs separately.
        var activated = new HashSet<Integer>();
        for (Activation activation : payment.activations()) {
            if (activation.source() < 0 || activation.source() >= fixture.lands().size()
                    || !activated.add(activation.source())) { throw new IllegalArgumentException("Invalid source"); }
            Card land = fixture.lands().get(activation.source());
            if (land.isTapped() || activation.ability() < 0 || activation.ability() >= land.getSpellAbilities().size()
                    || !land.getSpellAbilities().get(activation.ability()).isManaAbility()) {
                throw new IllegalArgumentException("Invalid mana ability");
            }
        }
        var spent = new HashSet<Integer>();
        for (Unit unit : payment.spent()) {
            if (unit.source() < 0 || unit.source() >= fixture.lands().size() || !spent.add(unit.source())) {
                throw new IllegalArgumentException("Invalid spent unit");
            }
        }
        ManaCostBeingPaid debt = new ManaCostBeingPaid(fixture.spell().getPayCosts().getTotalMana());
        int cursor = 0;
        for (Unit unit : payment.spent()) {
            if (cursor < payment.activations().size() && payment.activations().get(cursor).source() == unit.source()) {
                Activation activation = payment.activations().get(cursor++);
                SpellAbility ability = fixture.lands().get(activation.source()).getSpellAbilities().get(activation.ability());
                activate(fixture.player(), ability);
                fixture.spell().getPayingManaAbilities().add(ability);
            }
            Mana selected = null;
            for (Mana mana : fixture.player().getManaPool()) {
                if (unit(fixture.lands(), mana).equals(unit)) { selected = mana; break; }
            }
            assertNotNull(selected, "Recorded mana unit is unavailable");
            assertTrue(fixture.player().getManaPool().tryPayCostWithMana(fixture.spell(), debt, selected, false));
            fixture.spell().getPayingMana().add(selected);
        }
        assertEquals(cursor, payment.activations().size());
        assertTrue(debt.isPaid());
        return debt;
    }

    @Test public void capturesInternalPaymentAndReappliesItWithoutAi() {
        for (boolean floating : List.of(false, true)) {
            Outcome baseline = execute(fixture(new LobbyPlayerAi("first", null), floating));
            Fixture teacher = fixture(new LobbyPlayerAi("first", null) {
                @Override public Player createIngamePlayer(Game game, int id) {
                    Player player = new Player(getName(), game, id);
                    player.setFirstController(new RecordingController(game, player, this));
                    return player;
                }
            }, floating);
            Outcome recorded = execute(teacher);
            RecordingController recorder = (RecordingController) teacher.player().getController();
            assertEquals(recorded, baseline, "Recording changed native state or RNG");
            assertEquals(recorder.before, recorded.prepared());
            assertEquals(recorder.payment, recorded.payment());
            assertEquals(recorder.executions, 1, "Mana sources execute inside the AI, not through this override");
            assertEquals(recorder.payments, 1);
            if (floating) { assertTrue(recorder.poolQuestions > 0); }
            else { assertEquals(recorder.poolQuestions, 0); }
            assertEquals(recorded.payment().spent().size(), 2);
            assertEquals(recorded.payment().activations().size(), floating ? 1 : 2);
            assertEquals(recorded.resources().pool().size(), floating ? 1 : 0);

            Fixture replay = fixture(new LobbyPlayerRl("first", request -> {
                throw new AssertionError("Payment replay must not ask AI or client");
            }), floating);
            assertFalse(replay.player().getController().isAI());
            assertNotSame(replay.spell(), teacher.spell());
            for (int i = 0; i < replay.lands().size(); i++) { assertNotSame(replay.lands().get(i), teacher.lands().get(i)); }
            var before = OriginalCompositeChoice.observe(replay.game());
            assertEquals(before, recorded.before());
            assertThrows(IllegalArgumentException.class, () -> replayPayment(replay,
                    new Payment(List.of(new Activation(99, 0)), recorded.payment().spent())));
            assertEquals(OriginalCompositeChoice.observe(replay.game()), before);
            ManaCostBeingPaid debt = replayPayment(replay, recorder.payment);
            assertTrue(debt.isPaid());
            assertEquals(capture(replay.spell(), replay.lands()), recorded.payment());
            assertEquals(resources(replay), recorded.resources(), "Tap, exact pool units, spend order or debt diverged");
            assertTrue(replay.game().getStack().isEmpty(), "This proof replays payment, not the casting coordinator");
            assertFalse(replay.game().isGameOver());
            ((PlayerControllerRl) replay.player().getController()).closeObservation();
            System.out.println("PAYMENT floating=" + floating + " " + recorded.payment() + " " + recorded.resources());
        }
    }

    public static void main(String[] args) throws Exception {
        Path forge = Path.of(args[0]).toRealPath();
        for (Class<?> engineClass : List.of(PlayerControllerAi.class, forge.ai.ComputerUtilMana.class,
                forge.game.mana.ManaPool.class, CostTap.class)) {
            Path loaded = Path.of(engineClass.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            String module = engineClass.getPackageName().startsWith("forge.ai") ? "forge-ai" : "forge-game";
            assertEquals(loaded, forge.resolve(module + "/target/classes").toRealPath());
            System.out.println("ORIGIN " + engineClass.getName() + " " + loaded);
        }
        OriginalCompositeChoice.initialize(forge);
        new ManaPaymentBoundaryTest().capturesInternalPaymentAndReappliesItWithoutAi();
        System.out.println("PASS: public payment capture is neutral to native state/RNG; AI-free payment replay matches tap/pool/debt");
    }
}
