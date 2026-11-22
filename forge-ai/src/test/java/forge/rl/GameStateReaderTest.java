package forge.rl;

import forge.CardStorageReader;
import forge.ImageKeys;
import forge.StaticData;
import forge.ai.AiProfileUtil;
import forge.ai.LobbyPlayerAi;
import forge.card.CardStateName;
import forge.card.CardType;
import forge.card.mana.ManaCost;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.FileSection;
import forge.util.FileUtil;
import forge.util.Lang;
import forge.util.Localizer;
import forge.util.MyRandom;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

/** Native observation contracts, without a custom player controller. */
public class GameStateReaderTest {
    private record Fixture(Game game, Player observer, Player opponent) { }

    @BeforeClass
    public void initialize() {
        String res = Path.of("..", "forge-gui", "res").toAbsolutePath().normalize() + "/";
        Lang.createInstance("en-US");
        Localizer.getInstance().initialize("en-US", res + "languages");
        ImageKeys.initializeDirs("", Map.of(), "", "", "", "", "", "", "");
        FileSection.parseSections(FileUtil.readFile(res + "lists/TypeLists.txt"))
                .forEach(CardType.Helper::parseTypes);
        CardType.Constant.LOADED.set();
        new StaticData(new CardStorageReader(res + "cardsfolder/", null, false),
                new CardStorageReader(res + "tokenscripts/", null, false), null, null,
                res + "editions", Path.of("target", "custom-editions").toString(),
                res + "blockdata", res + "setlookup", "Latest Art All Editions",
                true, false, false, false);
        AiProfileUtil.loadAllProfiles(res + "ai");
    }

    private Fixture fixture() {
        var registered = List.of(
                new RegisteredPlayer(new Deck("first")).setPlayer(new LobbyPlayerAi("first", null)),
                new RegisteredPlayer(new Deck("second")).setPlayer(new LobbyPlayerAi("second", null)));
        Game game = new Match(new GameRules(GameType.Constructed), registered, "state reader").createGame();
        game.getRegisteredPlayers().forEach(player -> player.setLife(20, null));
        game.setAge(GameStage.Play);
        Player first = game.getRegisteredPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, first, false, 6);
        return new Fixture(game, first, game.getRegisteredPlayers().get(1));
    }

    private Card add(Fixture fixture, Player owner, ZoneType zone, String name) {
        Card card = CardFactory.getCard(StaticData.instance().getCommonCards().getCard(name), owner, fixture.game());
        owner.getZone(zone).add(card);
        return card;
    }

    private static List<Map<?, ?>> rows(Map<String, Object> state, String field) {
        return ((List<?>) state.get(field)).stream().map(row -> (Map<?, ?>) row).collect(Collectors.toList());
    }

    private static Map<?, ?> namedCard(Map<String, Object> state, String name) {
        return rows(state, "cards").stream().filter(card -> name.equals(card.get("name")))
                .findFirst().orElseThrow();
    }

    @Test
    public void ownHandVisibleAndHiddenChangesDoNotChangeObservation() {
        Fixture fixture = fixture();
        add(fixture, fixture.observer(), ZoneType.Hand, "Grizzly Bears");
        Card hidden = add(fixture, fixture.opponent(), ZoneType.Hand, "Lightning Bolt");
        Card top = add(fixture, fixture.opponent(), ZoneType.Library, "Forest");
        Card bottom = add(fixture, fixture.opponent(), ZoneType.Library, "Mountain");
        var reader = new GameStateReader(fixture.game(), fixture.observer());
        var before = reader.snapshot();
        Map<?, ?> ownHandCard = namedCard(before, "Grizzly Bears");
        assertTrue(ownHandCard.containsKey("oracle_text"));
        assertNotNull(ownHandCard.get("oracle_text"));
        assertNull(ownHandCard.get("tapped"));
        assertNull(ownHandCard.get("damage"));
        assertNull(ownHandCard.get("summoning_sick"));
        assertFalse(before.toString().contains("Lightning Bolt"));
        assertFalse(before.toString().contains("Forest"));
        assertFalse(before.toString().contains("Mountain"));
        assertEquals(rows(before, "cards").size(), 1);
        Map<?, ?> opponentHand = rows(before, "zones").stream()
                .filter(zone -> "p1".equals(zone.get("owner")) && "hand".equals(zone.get("zone")))
                .findFirst().orElseThrow();
        assertEquals(opponentHand.get("count"), 1);
        assertEquals(opponentHand.get("unknown_count"), 1);
        assertEquals(opponentHand.get("cards"), List.of());

        hidden.setName("Counterspell");
        fixture.opponent().getZone(ZoneType.Library).setCards(List.of(bottom, top));
        assertEquals(reader.snapshot(), before, "Hidden identity and physical library order must not be inputs");
    }

    @Test
    public void publicFaceDownObjectDoesNotRevealItsIdentity() {
        Fixture fixture = fixture();
        Card hidden = add(fixture, fixture.opponent(), ZoneType.Battlefield, "Grizzly Bears");
        assertTrue(hidden.turnFaceDown(true));
        var state = new GameStateReader(fixture.game(), fixture.observer()).snapshot();
        assertEquals(rows(state, "cards").size(), 1);
        Map<?, ?> card = rows(state, "cards").get(0);
        assertEquals(card.get("face_down"), true);
        assertEquals(card.get("identity_known"), false);
        assertNull(card.get("name"));
        assertNull(card.get("rules_text"));
        assertFalse(card.containsKey("abilities"));
        assertNull(card.get("oracle_text"));
        assertNull(card.get("mana_cost"));
        assertFalse(state.toString().contains("Grizzly Bears"));
        assertEquals(card.get("power"), hidden.getNetPower());
        assertEquals(card.get("toughness"), hidden.getNetToughness());

        var ownerState = new GameStateReader(fixture.game(), fixture.opponent()).snapshot();
        Map<?, ?> ownerCard = rows(ownerState, "cards").get(0);
        assertEquals(ownerCard.get("identity_known"), true);
        Map<?, ?> knownFace = (Map<?, ?>) ownerCard.get("known_face");
        assertEquals(knownFace.get("name"), "Grizzly Bears");
        assertNotNull(knownFace.get("oracle_text"));
        assertNotNull(knownFace.get("mana_cost"));
        assertEquals(ownerCard.get("power"), 2);
        assertEquals(ownerCard.get("toughness"), 2);

        var original = hidden.getState(CardStateName.Original);
        original.setName("Different secret creature");
        original.setManaCost(new ManaCost("7 G G"));
        original.setBasePower(9);
        original.setBaseToughness(9);
        original.setOracleText("A secret printed rule must not be visible.");
        assertEquals(new GameStateReader(fixture.game(), fixture.observer()).snapshot(), state,
                "Changing the hidden face must not change the opponent's current observation");
    }

    @Test
    public void snapshotIsDetachedDataAndReadDoesNotMutateNativeStateOrRandom() {
        Fixture fixture = fixture();
        Card card = add(fixture, fixture.observer(), ZoneType.Battlefield, "Grizzly Bears");
        var reader = new GameStateReader(fixture.game(), fixture.observer());
        MyRandom.setRandom(new Random(73));
        var state = reader.snapshot();
        assertEquals(MyRandom.getRandom().nextLong(), new Random(73).nextLong());
        assertFalse(card.isTapped());
        assertEquals(fixture.observer().getLife(), 20);
        assertSame(card.getZone(), fixture.observer().getZone(ZoneType.Battlefield));
        assertDetachedData(state);
        Map<?, ?> beforeCard = namedCard(state, "Grizzly Bears");
        assertEquals(beforeCard.get("tapped"), false);
        card.setTapped(true);
        fixture.observer().setLife(13, null);
        assertEquals(beforeCard.get("tapped"), false);
        assertEquals(rows(state, "players").get(0).get("life"), 20);
        var after = reader.snapshot();
        assertEquals(namedCard(after, "Grizzly Bears").get("tapped"), true);
        assertEquals(rows(after, "players").get(0).get("life"), 13);
    }

    @Test
    public void terminalResultUsesRegisteredSeatsAndRequiresGameOver() {
        var fixture = fixture();
        assertThrows(IllegalStateException.class, () -> GameStateReader.terminalOutcome(fixture.game()));
        fixture.observer().concede();
        fixture.game().setGameOver(GameEndReason.AllOpponentsLost);
        var result = GameStateReader.terminalOutcome(fixture.game());
        assertEquals(result.get("winner_seats"), List.of(1));
        assertEquals(result.get("draw"), false);
    }

    @Test
    public void rejectsObserverFromAnotherGame() {
        Fixture fixture = fixture();
        Player foreign = fixture().observer();
        assertThrows(IllegalArgumentException.class, () -> new GameStateReader(fixture.game(), foreign).snapshot());
    }

    @Test
    public void phasedOutPermanentRemainsAnObservableBattlefieldObject() {
        Fixture fixture = fixture();
        Card card = add(fixture, fixture.observer(), ZoneType.Battlefield, "Grizzly Bears");
        card.setPhasedOut(fixture.observer());
        assertFalse(fixture.observer().getCardsIn(ZoneType.Battlefield).contains(card),
                "Fixture must exercise the default native filter for phased-out cards");
        var state = new GameStateReader(fixture.game(), fixture.observer()).snapshot();
        Map<?, ?> observed = namedCard(state, "Grizzly Bears");
        assertEquals(observed.get("zone"), "battlefield");
        assertEquals(observed.get("phased_out"), true);
        Map<?, ?> battlefield = rows(state, "zones").stream()
                .filter(zone -> "p0".equals(zone.get("owner")) && "battlefield".equals(zone.get("zone")))
                .findFirst().orElseThrow();
        assertEquals(battlefield.get("count"), 1);
        assertEquals(battlefield.get("unknown_count"), 0);
        assertEquals(battlefield.get("cards"), List.of(observed.get("id")));
        assertTrue(card.isPhasedOut());
        assertSame(card.getPhasedOut(), fixture.observer());
        assertSame(card.getZone(), fixture.observer().getZone(ZoneType.Battlefield));
    }

    @Test
    public void combatAndStackSourcesReferenceCurrentObjects() {
        Fixture fixture = fixture();
        Card attacker = add(fixture, fixture.observer(), ZoneType.Battlefield, "Grizzly Bears");
        Card blocker = add(fixture, fixture.opponent(), ZoneType.Battlefield, "Runeclaw Bear");
        Combat combat = new Combat(fixture.observer());
        fixture.game().getPhaseHandler().setCombat(combat);
        combat.addAttacker(attacker, fixture.opponent());
        combat.addBlocker(attacker, blocker);
        Card source = add(fixture, fixture.observer(), ZoneType.Hand, "Centaur Courser");
        SpellAbility ability = AbilityUtils.getBasicSpellsFromPlayEffect(source, fixture.observer()).get(0);
        ability.setHostCard(fixture.game().getAction().moveToStack(source, ability));
        fixture.game().getStack().add(ability);
        Card counterSource = add(fixture, fixture.opponent(), ZoneType.Hand, "Counterspell");
        SpellAbility counter = AbilityUtils.getBasicSpellsFromPlayEffect(counterSource, fixture.opponent()).get(0);
        counter.getTargets().add(ability);
        counter.setHostCard(fixture.game().getAction().moveToStack(counterSource, counter));
        fixture.game().getStack().add(counter);
        var state = new GameStateReader(fixture.game(), fixture.observer()).snapshot();
        Set<Object> ids = rows(state, "cards").stream().map(card -> card.get("id")).collect(Collectors.toSet());
        ids.addAll(rows(state, "players").stream().map(player -> player.get("id")).toList());
        assertEquals(rows(state, "stack").size(), 2);
        Object centaurSourceId = namedCard(state, "Centaur Courser").get("id");
        Map<?, ?> stackItem = rows(state, "stack").stream()
                .filter(item -> centaurSourceId.equals(item.get("source"))).findFirst().orElseThrow();
        assertTrue(ids.contains(stackItem.get("source")));
        assertNotNull(stackItem.get("description"));
        Object counterSourceId = namedCard(state, "Counterspell").get("id");
        Map<?, ?> counterItem = rows(state, "stack").stream()
                .filter(item -> counterSourceId.equals(item.get("source"))).findFirst().orElseThrow();
        assertNotEquals(counterItem.get("id"), stackItem.get("id"));
        assertEquals(counterItem.get("targets"), List.of(Map.of("kind", "stack", "reference", stackItem.get("id"))));
        assertEquals(rows(state, "combat").size(), 1);
        Map<?, ?> assignment = rows(state, "combat").get(0);
        assertEquals(assignment.get("attacker"), namedCard(state, "Grizzly Bears").get("id"));
        assertTrue(ids.contains(assignment.get("defender")));
        assertEquals(assignment.get("blockers"), List.of(namedCard(state, "Runeclaw Bear").get("id")));
        assertDetachedData(state);

        Card stackSource = ability.getHostCard();
        stackSource.getZone().remove(stackSource);
        assertTrue(stackSource.getView().canBeShownTo(fixture.observer().getView()),
                "Last-known source view still has Stack visibility in this fixture");
        var withoutSource = new GameStateReader(fixture.game(), fixture.observer()).snapshot();
        Map<?, ?> unboundItem = rows(withoutSource, "stack").stream()
                .filter(item -> stackItem.get("id").equals(item.get("id"))).findFirst().orElseThrow();
        assertNull(unboundItem.get("source"));
        assertNull(unboundItem.get("description"), "An unbound source must not disclose text through its LKI view");
    }

    private static void assertDetachedData(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> {
                assertTrue(key instanceof String, "Wire keys must be strings");
                assertDetachedData(item);
            });
            return;
        }
        if (value instanceof List<?> list) {
            list.forEach(GameStateReaderTest::assertDetachedData);
            return;
        }
        fail("Live native object escaped: " + value.getClass().getName());
    }
}
