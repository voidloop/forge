package forge.rl;

import forge.deck.Deck;
import forge.LobbyPlayer;
import forge.ai.LobbyPlayerAi;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlaySpellAbility;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.LandAbility;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.testng.Assert.*;

/** Restricted fixture: basic lands in hand, empty stack, no spells/activated actions. */
public class BasicLandPriorityTest {
    record Fixture(Game game, Player player, Player opponent, Card forest, Card island) { }

    @BeforeClass public void initialize() { OriginalCompositeChoice.initialize(Path.of("..")); }

    private static Fixture fixture(IPlayerDecisionCallback callback) {
        return fixture(new LobbyPlayerRl("first", callback));
    }

    static Fixture fixture(LobbyPlayer lobby) {
        var seats = List.of(
                new RegisteredPlayer(new Deck("first")).setPlayer(lobby),
                new RegisteredPlayer(new Deck("second")).setPlayer(new LobbyPlayerRl("second", request -> {
                    throw new AssertionError("Unexpected opponent request");
                })));
        Game game = new Match(new GameRules(GameType.Constructed), seats, "basic land priority only").createGame();
        game.getRegisteredPlayers().forEach(player -> player.setLife(20, null));
        game.setAge(GameStage.Play);
        Player player = game.getRegisteredPlayers().get(0);
        Player opponent = game.getRegisteredPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, player, false, 1);
        game.getPhaseHandler().setPriority(player);
        return new Fixture(game, player, opponent, OriginalCompositeChoice.add(game, player, "Forest"),
                OriginalCompositeChoice.add(game, player, "Island"));
    }

    private static List<SpellAbility> nativeLandOptions(Player player) {
        List<SpellAbility> candidates = new ArrayList<>();
        for (Card card : player.getCardsIn(ZoneType.Hand)) {
            for (SpellAbility ability : card.getSpellAbilities()) {
                if (!ability.isLandAbility()) { continue; }
                ability.setActivatingPlayer(player);
                if (ability.canPlay()) { candidates.add(ability); }
            }
        }
        return candidates;
    }

    @Test public void nativeApiSuppliesCandidatesTimingAndExecution() {
        Fixture fixture = fixture(request -> { throw new AssertionError("Native proof must not ask client"); });
        var phase = fixture.game().getPhaseHandler();
        assertEquals(nativeLandOptions(fixture.player()).size(), 2);
        phase.devModeSet(PhaseType.COMBAT_BEGIN, fixture.player(), false, 1);
        assertTrue(nativeLandOptions(fixture.player()).isEmpty());
        phase.devModeSet(PhaseType.MAIN1, fixture.opponent(), false, 1);
        phase.setPriority(fixture.player());
        assertTrue(nativeLandOptions(fixture.player()).isEmpty());
        phase.devModeSet(PhaseType.MAIN1, fixture.player(), false, 1);
        SpellAbility land = nativeLandOptions(fixture.player()).get(0);
        assertTrue(PlaySpellAbility.playSpellAbility(fixture.player().getController(), fixture.player(), land));
        assertTrue(fixture.game().getCardState(fixture.forest(), null).isInZone(ZoneType.Battlefield));
        assertEquals(fixture.player().getLandsPlayedThisTurn(), 1);
        assertTrue(nativeLandOptions(fixture.player()).isEmpty());
    }

    @Test public void nativeAiReturnsLandBeforeExecutionAndThenPasses() {
        Fixture fixture = fixture(new LobbyPlayerAi("first", null));
        var controller = fixture.player().getController();
        var before = OriginalCompositeChoice.observe(fixture.game());
        List<SpellAbility> chosen = controller.chooseSpellAbilityToPlay();
        assertNotNull(chosen);
        assertEquals(chosen.size(), 1);
        SpellAbility land = chosen.get(0);
        assertTrue(land.isLandAbility());
        assertTrue(land.getHostCard() == fixture.forest() || land.getHostCard() == fixture.island());
        assertTrue(land.getHostCard().getSpellAbilities().stream().anyMatch(ability -> ability == land));
        assertEquals(OriginalCompositeChoice.observe(fixture.game()), before, "Choosing executed the land");
        fixture.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_BEGIN, fixture.player(), false, 1);
        assertTrue(controller.playChosenSpellAbility(land), "Native AI reports true even when timing prevents play");
        assertEquals(fixture.player().getLandsPlayedThisTurn(), 0);
        assertTrue(land.getHostCard().isInZone(ZoneType.Hand));
        fixture.game().getPhaseHandler().devModeSet(PhaseType.MAIN1, fixture.player(), false, 1);
        assertTrue(controller.playChosenSpellAbility(land));
        assertTrue(fixture.game().getCardState(land.getHostCard(), null).isInZone(ZoneType.Battlefield));
        assertEquals(fixture.player().getLandsPlayedThisTurn(), 1);
        assertNull(controller.chooseSpellAbilityToPlay());
        System.out.println("PASS: native AI returns a bound land before execution; next priority passes at the land limit");
    }

    @Test public void clientCanPassChooseLandAndReceivePriorityAgain() {
        List<Map<?, ?>> requests = new ArrayList<>();
        Fixture fixture = fixture(envelope -> {
            assertEquals(envelope.keySet(), java.util.Set.of("state", "options"));
            Map<?, ?> request = (Map<?, ?>) envelope.get("options");
            requests.add(request);
            assertEquals(request.get("kind"), "priority");
            assertEquals(request.get("scope"), "ordinary_lands_creatures_mana");
            return List.of(requests.size() == 2 ? 2 : 0);
        });
        var controller = fixture.player().getController();
        assertNull(controller.chooseSpellAbilityToPlay());
        assertEquals(fixture.player().getLandsPlayedThisTurn(), 0);
        var choice = controller.chooseSpellAbilityToPlay();
        assertEquals(choice.size(), 1);
        assertSame(choice.get(0).getHostCard(), fixture.island());
        assertTrue(controller.playChosenSpellAbility(choice.get(0)));
        assertTrue(fixture.game().getCardState(fixture.island(), null).isInZone(ZoneType.Battlefield));
        assertTrue(fixture.forest().isInZone(ZoneType.Hand));
        assertEquals(fixture.player().getLandsPlayedThisTurn(), 1);
        assertNull(controller.chooseSpellAbilityToPlay());
        assertEquals(((List<?>) requests.get(0).get("items")).stream()
                .map(item -> ((Map<?, ?>) item).get("type")).toList(), List.of("pass", "play_land", "play_land"));
        assertEquals(((Map<?, ?>) ((List<?>) requests.get(0).get("items")).get(2)).get("name"), "Island");
        assertEquals(((List<?>) requests.get(2).get("items")).size(), 2);
        for (boolean opponentTurn : List.of(false, true)) {
            Fixture restricted = fixture(envelope -> {
                Map<?, ?> request = (Map<?, ?>) envelope.get("options");
                assertEquals(request.get("items"), List.of(Map.of("type", "pass")));
                return List.of(0);
            });
            restricted.game().getPhaseHandler().devModeSet(opponentTurn ? PhaseType.MAIN1 : PhaseType.COMBAT_BEGIN,
                    opponentTurn ? restricted.opponent() : restricted.player(), false, 1);
            restricted.game().getPhaseHandler().setPriority(restricted.player());
            assertNull(restricted.player().getController().chooseSpellAbilityToPlay());
        }
    }

    @Test public void invalidStaleUnboundAndUnsupportedChoicesFailExplicitly() {
        for (List<Integer> invalid : List.of(List.<Integer>of(), List.of(-1), List.of(3), List.of(1, 2))) {
            Fixture fixture = fixture(request -> invalid);
            var before = OriginalCompositeChoice.observe(fixture.game());
            assertThrows(IllegalArgumentException.class, () -> fixture.player().getController().chooseSpellAbilityToPlay());
            assertEquals(OriginalCompositeChoice.observe(fixture.game()), before);
            assertThrows(IllegalArgumentException.class, () -> fixture.player().getController().chooseSpellAbilityToPlay());
        }
        Fixture unbound = fixture(request -> List.of(0));
        SpellAbility unselected = nativeLandOptions(unbound.player()).get(0);
        assertThrows(IllegalArgumentException.class, () -> unbound.player().getController().playChosenSpellAbility(unselected));
        Fixture stale = fixture(request -> List.of(1));
        SpellAbility selected = stale.player().getController().chooseSpellAbilityToPlay().get(0);
        stale.game().getPhaseHandler().devModeSet(PhaseType.COMBAT_BEGIN, stale.player(), false, 1);
        assertThrows(IllegalArgumentException.class, () -> stale.player().getController().playChosenSpellAbility(selected));
        assertEquals(stale.player().getLandsPlayedThisTurn(), 0);
        assertTrue(stale.forest().isInZone(ZoneType.Hand));
        Fixture repeated = fixture(request -> List.of(1));
        SpellAbility land = repeated.player().getController().chooseSpellAbilityToPlay().get(0);
        assertTrue(repeated.player().getController().playChosenSpellAbility(land));
        assertThrows(IllegalArgumentException.class, () -> repeated.player().getController().playChosenSpellAbility(land));
        assertEquals(repeated.player().getLandsPlayedThisTurn(), 1);
        Fixture wrongPriority = fixture(request -> { throw new AssertionError("No priority must not ask client"); });
        wrongPriority.game().getPhaseHandler().setPriority(wrongPriority.opponent());
        assertThrows(IllegalStateException.class, () -> wrongPriority.player().getController().chooseSpellAbilityToPlay());
        Fixture unsupported = fixture(request -> { throw new AssertionError("Unsupported hand must not ask client"); });
        OriginalCompositeChoice.add(unsupported.game(), unsupported.player(), "Llanowar Elves");
        assertThrows(UnsupportedOperationException.class, () -> unsupported.player().getController().chooseSpellAbilityToPlay());
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toRealPath();
        for (Class<?> type : List.of(Game.class, LandAbility.class, PlaySpellAbility.class)) {
            assertEquals(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath(),
                    root.resolve("forge-game/target/classes").toRealPath());
        }
        OriginalCompositeChoice.initialize(root);
        var test = new BasicLandPriorityTest();
        test.nativeApiSuppliesCandidatesTimingAndExecution();
        test.nativeAiReturnsLandBeforeExecutionAndThenPasses();
        test.clientCanPassChooseLandAndReceivePriorityAgain();
        test.invalidStaleUnboundAndUnsupportedChoicesFailExplicitly();
        System.out.println("PASS: native basic-land candidates and RL priority verified; timing, execution, turn limit and invalid choices checked");
    }
}
