package forge.ai.probe;

import forge.ImageKeys;
import forge.StaticData;
import forge.CardStorageReader;
import forge.ai.AiController;
import forge.ai.AiCostDecision;
import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilMana;
import forge.game.ability.AbilityApiBased;
import forge.game.cost.CostPayment;
import forge.game.cost.CostPayLife;
import forge.game.cost.CostSacrifice;
import forge.game.cost.CostTap;
import forge.game.cost.PaymentDecision;
import forge.util.collect.FCollection;
import forge.ai.AiPlayDecision;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.rl.EffectCastProbe;
import forge.rl.PlayerControllerRecordingAi;
import forge.card.CardType;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.ability.SpellApiBased;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.cost.Cost;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.Spell;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.FileSection;
import forge.util.FileUtil;
import forge.util.Lang;
import forge.util.Localizer;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.testng.Assert.*;

public class AiControllerProbeTest {
    @BeforeClass
    public void initializeForge() {
        String res = "../forge-gui/res/";
        Lang.createInstance("en-US");
        Localizer.getInstance().initialize("en-US", res + "languages");
        ImageKeys.initializeDirs("", Map.of(), "", "", "", "", "", "", "");
        FileSection.parseSections(FileUtil.readFile(res + "lists/TypeLists.txt"))
                .forEach(CardType.Helper::parseTypes);
        CardType.Constant.LOADED.set();
        new StaticData(new CardStorageReader(res + "cardsfolder/", null, false),
                new CardStorageReader(res + "tokenscripts/", null, false), null, null,
                res + "editions", "target/custom-editions", res + "blockdata", res + "setlookup",
                "Latest Art All Editions", true, false, false, false);
    }

    private static final class ProbeLobby extends LobbyPlayerAi {
        private final Probe probe = new Probe();
        private ProbeLobby(String name) { super(name, null); }

        @Override
        public Player createIngamePlayer(Game game, int id) {
            Player player = new Player(getName(), game, id);
            player.setFirstController(new PlayerControllerRecordingAi(game, player, this, probe));
            return player;
        }
    }

    private Game game(boolean probes) {
        List<RegisteredPlayer> players = List.of(
                new RegisteredPlayer(new Deck("first")).setPlayer(probes ? new ProbeLobby("first") : new LobbyPlayerAi("first", null)),
                new RegisteredPlayer(new Deck("second")).setPlayer(probes ? new ProbeLobby("second") : new LobbyPlayerAi("second", null)));
        Game game = new Match(new GameRules(GameType.Constructed), players, "AI probes").createGame();
        game.getRegisteredPlayers().forEach(player -> player.setLife(20, null));
        game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, game.getRegisteredPlayers().get(0), false, 6);
        return game;
    }

    private Card add(Game game, Player owner, String name, ZoneType zone) {
        Card card = CardFactory.getCard(StaticData.instance().getCommonCards().getCard(name), owner, game);
        owner.getZone(zone).add(card);
        return card;
    }

    private record Choice(ZoneType beforeZone, List<Card> beforeTargets,
                          AiPlayDecision decision, List<Card> targets) { }

    private static final class Probe implements EffectCastProbe {
        private ZoneType beforeZone;
        private List<Card> beforeTargets;
        private Spell pending;
        private final List<Choice> choices = new ArrayList<>();
        private final List<Boolean> completed = new ArrayList<>();
        private final List<String> events = new ArrayList<>();

        @Override
        public void beforeDecision(Spell spell, boolean mandatory, boolean withoutMana) {
            pending = spell;
            beforeZone = spell.getHostCard().getZone().getZoneType();
            beforeTargets = List.copyOf(spell.getTargets().getTargetCards());
            events.add("before");
        }

        @Override
        public void decided(Spell spell, AiPlayDecision decision) {
            assertSame(spell, pending);
            choices.add(new Choice(beforeZone, beforeTargets, decision,
                    List.copyOf(spell.getTargets().getTargetCards())));
            events.add("decided");
        }

        @Override
        public void completed(Spell spell, boolean cast) {
            assertSame(spell, pending);
            completed.add(cast);
            events.add("completed");
        }
    }

    private Probe probe(Player player) {
        return ((ProbeLobby) player.getLobbyPlayer()).probe;
    }

    private static final class InjectedController extends PlayerControllerAi {
        private InjectedController(Game game, Player player, AiController ai) {
            super(game, player, player.getLobbyPlayer(), ai);
        }
    }

    @Test
    public void validatesInjectedAiAndKeepsStandardConstructor() {
        Game game = game(false);
        Player first = game.getRegisteredPlayers().get(0);
        Player second = game.getRegisteredPlayers().get(1);
        PlayerControllerAi standard = new PlayerControllerAi(game, first, first.getLobbyPlayer());
        assertEquals(standard.getAi().getClass(), AiController.class);
        assertSame(standard.getAi().getPlayer(), first);
        assertSame(standard.getAi().getGame(), game);
        Card target = add(game, second, "Weapons Vendor", ZoneType.Battlefield);
        Card source = add(game, first, "Get Lost", ZoneType.Hand);
        SpellAbility spell = AbilityUtils.getBasicSpellsFromPlayEffect(source, first).get(0).copyWithNoManaCost();
        assertTrue(first.getController().playSaFromPlayEffect(spell));
        assertEquals(game.getStack().peekAbility().getTargets().getTargetCards(), List.of(target));
        assertThrows(NullPointerException.class, () -> new InjectedController(game, first, null));
        assertThrows(IllegalArgumentException.class,
                () -> new InjectedController(game, first, new AiController(second, game)));
        assertThrows(IllegalArgumentException.class,
                () -> new InjectedController(game, first, new AiController(first, game(false))));
    }

    @DataProvider(name = "effectCasts")
    public Object[][] effectCasts() {
        return new Object[][] {{0, false, false}, {1, false, false},
                {0, true, false}, {1, true, false}, {0, true, true}, {1, true, true}};
    }

    @Test(dataProvider = "effectCasts")
    public void recordsNativeChoiceBeforeSeparateExecution(int seat, boolean accept, boolean failPayment) {
        Game game = game(true);
        Player player = game.getRegisteredPlayers().get(seat);
        Player opponent = game.getRegisteredPlayers().get(1 - seat);
        Probe probe = probe(player);
        Probe peer = probe(opponent);
        Card target = accept ? add(game, opponent, "Weapons Vendor", ZoneType.Battlefield) : null;
        if (failPayment) {
            add(game, opponent, "Arachne, Psionic Weaver", ZoneType.Battlefield).setChosenType("Artifact");
        }
        Card source = add(game, player, failPayment ? "White Auracite" : "Get Lost", ZoneType.Hand);
        Spell spell = (Spell) AbilityUtils.getBasicSpellsFromPlayEffect(source, player).get(0).copyWithNoManaCost();
        assertEquals(((PlayerControllerAi) player.getController()).getAi().canPlayFromEffectAI(spell, false, true) == AiPlayDecision.WillPlay, accept);
        assertTrue(probe.choices.isEmpty());
        spell.resetTargets();
        assertEquals(player.getController().playSaFromPlayEffect(spell), accept && !failPayment);
        assertEquals(probe.events, List.of("before", "decided", "completed"));
        assertEquals(probe.completed, List.of(accept && !failPayment));
        assertEquals(probe.choices.size(), 1);
        Choice choice = probe.choices.get(0);
        assertEquals(choice.beforeZone(), ZoneType.Hand);
        assertTrue(choice.beforeTargets().isEmpty());
        assertEquals(choice.decision() == AiPlayDecision.WillPlay, accept);
        assertEquals(choice.targets(), target == null || failPayment ? List.of() : List.of(target));
        assertEquals(game.getStack().isEmpty(), !accept || failPayment);
        assertEquals(game.getCardState(source, null).getZone().getZoneType(),
                accept && !failPayment ? ZoneType.Stack : ZoneType.Hand);
        assertTrue(peer.events.isEmpty());
        assertTrue(peer.choices.isEmpty());

        if (accept && !failPayment) {
            assertEquals(game.getStack().peekAbility().getTargets().getTargetCards(), List.of(target));
        }
    }

    @Test
    public void nestedCopyEvaluationDoesNotBecomeAnotherTeacherChoice() {
        forge.util.MyRandom.setRandom(new Random(0));
        Game game = game(true);
        Player player = game.getRegisteredPlayers().get(0);
        Player opponent = game.getRegisteredPlayers().get(1);
        Probe probe = probe(player);
        Card original = add(game, opponent, "Lightning Strike", ZoneType.Hand);
        SpellAbility top = AbilityUtils.getBasicSpellsFromPlayEffect(original, opponent).get(0);
        top.getTargets().add(player);
        top.setHostCard(game.getAction().moveToStack(original, top));
        game.getStack().add(top);
        Card source = add(game, player, "Insidious Will", ZoneType.Hand);
        Spell spell = (Spell) AbilityUtils.getBasicSpellsFromPlayEffect(source, player).get(0).copyWithNoManaCost();
        assertTrue(player.getController().playSaFromPlayEffect(spell));
        assertEquals(probe.events, List.of("before", "decided", "completed"));
        assertEquals(probe.choices.size(), 1);
        assertEquals(probe.choices.get(0).decision(), AiPlayDecision.WillPlay);
        assertEquals(spell.getChosenList().get(0).getApi(), ApiType.CopySpellAbility);
        assertSame(game.getStack().peekAbility(), spell);

    }

    @Test
    public void nativeExceptionPropagatesAndClosesProbeScope() {
        Game game = game(true);
        Player player = game.getRegisteredPlayers().get(0);
        Probe probe = probe(player);
        Card source = add(game, player, "White Auracite", ZoneType.Hand);
        RuntimeException failure = new IllegalStateException("native evaluation failed");
        AtomicBoolean armed = new AtomicBoolean();
        Spell spell = new SpellApiBased(ApiType.Draw, source, new Cost("0", false), null, Map.of("NumCards", "1")) {
            @Override public ApiType getApi() {
                if (armed.get()) { throw failure; }
                return super.getApi();
            }
        };
        spell.setActivatingPlayer(player);
        armed.set(true);
        RuntimeException actual = expectThrows(RuntimeException.class,
                () -> player.getController().playSaFromPlayEffect(spell));
        assertSame(actual, failure);
        assertEquals(probe.events, List.of("before"));
        assertTrue(probe.completed.isEmpty());
        assertTrue(probe.choices.isEmpty());

        assertTrue(game.getStack().isEmpty());
        assertTrue(source.isInZone(ZoneType.Hand));
        armed.set(false);
        ((PlayerControllerAi) player.getController()).getAi().canPlayFromEffectAI(spell, false, true);
        assertEquals(probe.events, List.of("before"), "Failed cast scope must be closed");
    }
    private record CostCall(Player payer, SpellAbility ability, boolean effect, boolean payMana) { }

    private static final class CostRecordingController extends PlayerControllerAi {
        private final List<CostCall> calls = new ArrayList<>();
        private final List<Card> sacrificed = new ArrayList<>();
        private final List<Integer> lifeBefore = new ArrayList<>();
        private final List<Integer> lifeChoices = new ArrayList<>();
        private int taps;
        private RuntimeException failure;

        private CostRecordingController(Game game, Player player) {
            super(game, player, player.getLobbyPlayer());
        }

        @Override
        protected AiCostDecision createAiCostDecision(Player payer, SpellAbility ability, boolean effect, boolean payMana) {
            calls.add(new CostCall(payer, ability, effect, payMana));
            return new AiCostDecision(payer, ability, effect, payMana) {
                @Override public PaymentDecision visit(CostSacrifice cost) {
                    PaymentDecision answer = super.visit(cost);
                    if (answer != null) {
                        for (Card card : answer.cards) {
                            assertTrue(card.isInZone(ZoneType.Battlefield));
                            sacrificed.add(card);
                        }
                    }
                    return answer;
                }

                @Override public PaymentDecision visit(CostPayLife cost) {
                    lifeBefore.add(payer.getLife());
                    if (failure != null) { throw failure; }
                    PaymentDecision answer = super.visit(cost);
                    lifeChoices.add(answer == null ? null : answer.c);
                    return answer;
                }

                @Override public PaymentDecision visit(CostTap cost) {
                    PaymentDecision answer = super.visit(cost);
                    taps++;
                    return answer;
                }
            };
        }
    }

    private CostRecordingController costController(Game game, Player player) {
        CostRecordingController controller = new CostRecordingController(game, player);
        player.dangerouslySetController(controller);
        return controller;
    }

    private SpellAbility costAbility(Game game, Player player, String cost) {
        Card source = add(game, player, "White Auracite", ZoneType.Battlefield);
        SpellAbility ability = new AbilityApiBased(ApiType.Draw, source, new Cost(cost, true), null,
                Map.of("NumCards", "0"));
        ability.setActivatingPlayer(player);
        return ability;
    }

    @DataProvider(name = "costPayments")
    public Object[][] costPayments() {
        return new Object[][] {{0, "stack"}, {1, "stack"}, {0, "chosen"}, {1, "chosen"},
                {0, "noStack"}, {1, "noStack"}, {0, "prevent"}, {1, "prevent"},
                {0, "factory"}, {1, "factory"}};
    }

    @Test(dataProvider = "costPayments")
    public void costExtensionObservesNativeAnswersBeforePayment(int seat, String route) {
        Game game = game(false);
        Player player = game.getRegisteredPlayers().get(seat);
        Player opponent = game.getRegisteredPlayers().get(1 - seat);
        CostRecordingController controller = costController(game, player);
        CostRecordingController peer = costController(game, opponent);
        Card creature = add(game, player, "Weapons Vendor", ZoneType.Battlefield);
        SpellAbility ability = costAbility(game, player, "Sac<1/Creature> PayLife<2>");
        boolean paid = switch (route) {
            case "stack" -> ComputerUtil.playStack(ability, player, game);
            case "chosen" -> ComputerUtil.handlePlayingSpellAbility(player, ability, null);
            case "noStack" -> ComputerUtil.playNoStack(player, ability, game, true);
            case "prevent" -> controller.payCostToPreventEffect(ability.getPayCosts(), ability, false,
                    new FCollection<>(List.of(player)));
            case "factory" -> new CostPayment(ability.getPayCosts(), ability).payComputerCosts(
                    controller.getCostDecisionMaker(player, ability, true, null));
            default -> throw new AssertionError(route);
        };
        assertTrue(paid);
        assertEquals(controller.calls, List.of(new CostCall(player, ability,
                !route.equals("stack") && !route.equals("chosen"), false)));
        assertEquals(controller.sacrificed, List.of(creature));
        assertEquals(controller.lifeBefore, List.of(20));
        assertEquals(controller.lifeChoices, List.of(2));
        assertEquals(player.getLife(), 18);
        assertTrue(game.getCardState(creature, null).isInZone(ZoneType.Graveyard));
        assertTrue(peer.calls.isEmpty());
    }

    @Test
    public void manaPaymentUsesExtensionOnlyForActualSourceCosts() {
        Game game = game(false);
        Player player = game.getRegisteredPlayers().get(0);
        CostRecordingController controller = costController(game, player);
        Card land = add(game, player, "Plains", ZoneType.Battlefield);
        SpellAbility ability = costAbility(game, player, "W");
        assertTrue(ComputerUtilMana.canPayManaCost(ability, player, 0, true));
        assertTrue(controller.calls.isEmpty());
        assertTrue(ComputerUtil.playNoStack(player, ability, game, true));
        assertEquals(controller.calls.size(), 2);
        assertEquals(controller.calls.get(0), new CostCall(player, ability, true, false));
        CostCall mana = controller.calls.get(1);
        assertSame(mana.payer(), player);
        assertSame(mana.ability().getHostCard(), land);
        assertTrue(mana.effect());
        assertTrue(mana.payMana());
        assertEquals(controller.taps, 1);
        assertTrue(land.isTapped());
    }

    @Test
    public void costExtensionPreservesPayerRefusalAndExceptions() {
        Game game = game(false);
        Player payer = game.getRegisteredPlayers().get(0);
        Player activator = game.getRegisteredPlayers().get(1);
        CostRecordingController controller = costController(game, payer);
        CostRecordingController peer = costController(game, activator);
        SpellAbility ability = costAbility(game, activator, "PayLife<21>");
        assertFalse(new CostPayment(ability.getPayCosts(), ability).payComputerCosts(
                AiCostDecision.forPayment(payer, ability, true, false)));
        assertEquals(controller.calls, List.of(new CostCall(payer, ability, true, false)));
        assertNull(controller.lifeChoices.get(0));
        assertEquals(payer.getLife(), 20);
        assertTrue(peer.calls.isEmpty());
        controller.failure = new IllegalStateException("cost probe failed");
        RuntimeException actual = expectThrows(RuntimeException.class, () ->
                new CostPayment(ability.getPayCosts(), ability).payComputerCosts(
                        AiCostDecision.forPayment(payer, ability, true, false)));
        assertSame(actual, controller.failure);
        assertEquals(payer.getLife(), 20);
    }


    @Test
    public void failedEvaluationIsObservableInsteadOfARecordedPass() throws Exception {
        Game game = game(false);
        Player player = game.getRegisteredPlayers().get(0);
        Card source = add(game, player, "Grizzly Bears", ZoneType.Hand);
        RuntimeException failure = new IllegalStateException("evaluation sentinel");
        Spell spell = new SpellApiBased(ApiType.Draw, source, new Cost("0", false), null, Map.of("NumCards", "1")) {
            @Override public ApiType getApi() {
                if (Thread.currentThread().getName().equals("Game AI Eval")) { throw failure; }
                return super.getApi();
            }
        };
        spell.setActivatingPlayer(player);
        AiController ai = new AiController(player, game) {
            @Override protected void onEvaluationFailure(Exception cause) {
                assertSame(cause.getCause(), failure);
                throw new IllegalStateException("incomplete", cause);
            }
        };
        var method = AiController.class.getDeclaredMethod("chooseSpellAbilityToPlayFromList", List.class, boolean.class);
        method.setAccessible(true);
        var actual = expectThrows(java.lang.reflect.InvocationTargetException.class,
                () -> method.invoke(ai, new ArrayList<>(List.of(spell)), true));
        assertEquals(actual.getCause().getMessage(), "incomplete");
    }
}
