package forge.ai.probe;

import forge.LobbyPlayer;
import forge.ImageKeys;
import forge.StaticData;
import forge.CardStorageReader;
import forge.ai.AiController;
import forge.ai.AiPlayDecision;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
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
        private ProbeLobby(String name) { super(name, null); }

        @Override
        public Player createIngamePlayer(Game game, int id) {
            Player player = new Player(getName(), game, id);
            player.setFirstController(new ProbeController(game, player, this, new ProbeAi(player, game)));
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

    private static final class ProbeAi extends AiController {
        private SpellAbility castingFromEffect;
        private int evaluations;
        private final List<Choice> choices = new ArrayList<>();

        private ProbeAi(Player player, Game game) { super(player, game); }

        @Override
        public AiPlayDecision canPlayFromEffectAI(Spell spell, boolean mandatory, boolean withoutMana) {
            evaluations++;
            ZoneType beforeZone = spell.getHostCard().getZone().getZoneType();
            List<Card> beforeTargets = List.copyOf(spell.getTargets().getTargetCards());
            AiPlayDecision decision = super.canPlayFromEffectAI(spell, mandatory, withoutMana);
            // Evaluations outside the actual cast workflow are not teacher answers.
            if (spell == castingFromEffect) {
                choices.add(new Choice(beforeZone, beforeTargets, decision,
                        List.copyOf(spell.getTargets().getTargetCards())));
            }
            return decision;
        }
    }

    private static final class ProbeController extends PlayerControllerAi {
        private final ProbeAi probe;

        private ProbeController(Game game, Player player, LobbyPlayer lobby, ProbeAi probe) {
            super(game, player, lobby, probe);
            this.probe = probe;
        }

        @Override
        public boolean playSaFromPlayEffect(SpellAbility ability) {
            SpellAbility previous = probe.castingFromEffect;
            probe.castingFromEffect = ability;
            try {
                return super.playSaFromPlayEffect(ability);
            } finally {
                probe.castingFromEffect = previous;
            }
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
        assertThrows(NullPointerException.class, () -> new ProbeController(game, first, first.getLobbyPlayer(), null));
        assertThrows(IllegalArgumentException.class,
                () -> new ProbeController(game, first, first.getLobbyPlayer(), new ProbeAi(second, game)));
        assertThrows(IllegalArgumentException.class,
                () -> new ProbeController(game, first, first.getLobbyPlayer(), new ProbeAi(first, game(false))));
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
        ProbeAi probe = (ProbeAi) ((PlayerControllerAi) player.getController()).getAi();
        ProbeAi peer = (ProbeAi) ((PlayerControllerAi) opponent.getController()).getAi();
        Card target = accept ? add(game, opponent, "Weapons Vendor", ZoneType.Battlefield) : null;
        if (failPayment) {
            add(game, opponent, "Arachne, Psionic Weaver", ZoneType.Battlefield).setChosenType("Artifact");
        }
        Card source = add(game, player, failPayment ? "White Auracite" : "Get Lost", ZoneType.Hand);
        Spell spell = (Spell) AbilityUtils.getBasicSpellsFromPlayEffect(source, player).get(0).copyWithNoManaCost();
        assertEquals(probe.canPlayFromEffectAI(spell, false, true) == AiPlayDecision.WillPlay, accept);
        assertTrue(probe.choices.isEmpty());
        spell.resetTargets();
        assertEquals(player.getController().playSaFromPlayEffect(spell), accept && !failPayment);
        assertEquals(probe.evaluations, 2);
        assertEquals(probe.choices.size(), 1);
        Choice choice = probe.choices.get(0);
        assertEquals(choice.beforeZone(), ZoneType.Hand);
        assertTrue(choice.beforeTargets().isEmpty());
        assertEquals(choice.decision() == AiPlayDecision.WillPlay, accept);
        assertEquals(choice.targets(), target == null || failPayment ? List.of() : List.of(target));
        assertEquals(game.getStack().isEmpty(), !accept || failPayment);
        assertEquals(game.getCardState(source, null).getZone().getZoneType(),
                accept && !failPayment ? ZoneType.Stack : ZoneType.Hand);
        assertEquals(peer.evaluations, 0);
        assertTrue(peer.choices.isEmpty());
        assertNull(probe.castingFromEffect);
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
        ProbeAi probe = (ProbeAi) ((PlayerControllerAi) player.getController()).getAi();
        Card original = add(game, opponent, "Lightning Strike", ZoneType.Hand);
        SpellAbility top = AbilityUtils.getBasicSpellsFromPlayEffect(original, opponent).get(0);
        top.getTargets().add(player);
        top.setHostCard(game.getAction().moveToStack(original, top));
        game.getStack().add(top);
        Card source = add(game, player, "Insidious Will", ZoneType.Hand);
        Spell spell = (Spell) AbilityUtils.getBasicSpellsFromPlayEffect(source, player).get(0).copyWithNoManaCost();
        assertTrue(player.getController().playSaFromPlayEffect(spell));
        assertTrue(probe.evaluations > 1, "Native copy evaluation must exercise the nested call");
        assertEquals(probe.choices.size(), 1);
        assertEquals(probe.choices.get(0).decision(), AiPlayDecision.WillPlay);
        assertEquals(spell.getChosenList().get(0).getApi(), ApiType.CopySpellAbility);
        assertSame(game.getStack().peekAbility(), spell);
        assertNull(probe.castingFromEffect);
    }

    @Test
    public void nativeExceptionPropagatesAndClosesProbeScope() {
        Game game = game(true);
        Player player = game.getRegisteredPlayers().get(0);
        ProbeAi probe = (ProbeAi) ((PlayerControllerAi) player.getController()).getAi();
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
        assertEquals(probe.evaluations, 1);
        assertTrue(probe.choices.isEmpty());
        assertNull(probe.castingFromEffect);
        assertTrue(game.getStack().isEmpty());
        assertTrue(source.isInZone(ZoneType.Hand));
    }
}
