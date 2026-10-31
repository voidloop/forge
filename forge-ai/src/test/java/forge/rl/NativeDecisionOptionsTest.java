package forge.rl;

import forge.deck.Deck;
import forge.LobbyPlayer;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.util.collect.FCollection;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.testng.Assert.*;

/** Exercise independent client selections through original native effects. */
public class NativeDecisionOptionsTest {
    private record Request(String kind, List<String> options, int min, int max, boolean optional) { }

    @BeforeClass public void initialize() { OriginalCompositeChoice.initialize(Path.of("..")); }

    static void resolve(Card host, Player player, String script) {
        SpellAbility ability = AbilityFactory.getAbility(script, host);
        ability.setActivatingPlayer(player);
        AbilityUtils.resolve(ability);
    }

    record Fixture(Game game, Player actor, Player opponent, Card host, Card first, Card second) { }

    private static Fixture fixture(IPlayerDecisionCallback callback) {
        return fixture(new LobbyPlayerRl("first", callback));
    }

    static Fixture fixture(LobbyPlayer actorLobby) {
        var seats = List.of(
                new RegisteredPlayer(new Deck("first")).setPlayer(actorLobby),
                new RegisteredPlayer(new Deck("second")).setPlayer(new LobbyPlayerRl("second", request -> {
                    throw new AssertionError("Unexpected opponent choice");
                })));
        Game game = new Match(new GameRules(GameType.Constructed), seats, "native options").createGame();
        game.getRegisteredPlayers().forEach(player -> player.setLife(20, null));
        game.setAge(GameStage.Play);
        Player actor = game.getRegisteredPlayers().get(0);
        Player opponent = game.getRegisteredPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, actor, false, 6);
        Card host = add(game, actor, "Forest", ZoneType.Battlefield);
        Card first = add(game, actor, "Grizzly Bears", ZoneType.Battlefield);
        Card second = add(game, actor, "Runeclaw Bear", ZoneType.Battlefield);
        add(game, opponent, "Air Elemental", ZoneType.Battlefield);
        add(game, actor, "Black Lotus", ZoneType.Library);
        add(game, opponent, "Lightning Bolt", ZoneType.Hand);
        return new Fixture(game, actor, opponent, host, first, second);
    }

    @Test public void effectsSupplyFilteredAndCurrentOptions() {
        List<Request> requests = new ArrayList<>();
        Fixture fixture = fixture(envelope -> {
            Map<?, ?> request = (Map<?, ?>) envelope.get("options");
            List<?> descriptions = (List<?>) request.get("items");
            List<String> names = descriptions.stream().map(option -> (String) ((Map<?, ?>) option).get("name")).toList();
            Request detached = new Request((String) request.get("kind"), names,
                    (int) request.get("min"), (int) request.get("max"), (boolean) request.get("optional"));
            requests.add(detached);
            System.out.println("RL OPTIONS " + detached);
            return detached.kind().equals("cards") ? List.of(1, 0) : List.of(0);
        });
        Card host = fixture.host();
        Player actor = fixture.actor();
        Player opponent = fixture.opponent();
        Card first = fixture.first();
        Card second = fixture.second();

        resolve(host, actor, "DB$ ChoosePlayer | Defined$ You | Choices$ Player.Opponent | DontNotify$ True");
        assertSame(host.getChosenPlayer(), opponent);
        assertEquals(requests.get(0), new Request("single_entity", List.of("second"), 1, 1, false));

        resolve(host, actor, "DB$ ChooseCard | Defined$ You | ChoiceZone$ Battlefield | Choices$ Creature.YouCtrl | Amount$ 2 | Mandatory$ True");
        assertEquals(requests.get(1), new Request("cards", List.of("Grizzly Bears", "Runeclaw Bear"), 2, 2, false));
        assertEquals(new ArrayList<>(host.getChosenCards()), List.of(second, first));

        String tap = "DB$ Tap | CardChoices$ Creature.YouCtrl+untapped | ChoiceAmount$ 1";
        resolve(host, actor, tap);
        assertTrue(first.isTapped());
        assertFalse(second.isTapped());
        assertFalse(host.isTapped());
        resolve(host, actor, tap);
        assertTrue(second.isTapped());
        assertEquals(requests.subList(2, 4), List.of(
                new Request("entities", List.of("Grizzly Bears", "Runeclaw Bear"), 1, 1, false),
                new Request("entities", List.of("Runeclaw Bear"), 1, 1, false)));
        assertEquals(requests.size(), 4);
    }

    private static Card add(Game game, Player player, String name, ZoneType zone) {
        Card card = OriginalCompositeChoice.add(game, player, name);
        if (zone != ZoneType.Hand) { card = game.getAction().moveTo(zone, card, null, null); }
        return card;
    }

    @Test public void validatesSelectionsAndOptionalDeclines() {
        String chooseTwo = "DB$ ChooseCard | Defined$ You | Choices$ Creature.YouCtrl | Amount$ 2 | Mandatory$ True";
        for (List<Integer> invalid : List.of(List.<Integer>of(), List.of(0), List.of(0, 0), List.of(-1, 0), List.of(0, 2), List.of(0, 1, 2))) {
            Fixture fixture = fixture(request -> invalid);
            assertThrows(IllegalArgumentException.class, () -> resolve(fixture.host(), fixture.actor(), chooseTwo));
            assertTrue(fixture.host().getChosenCards().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> fixture.actor().getController().chooseNumber(null, "stopped", 0, 1));
        }
        Fixture optional = fixture(request -> List.of());
        resolve(optional.host(), optional.actor(), "DB$ ChooseCard | Defined$ You | Choices$ Creature.YouCtrl | Amount$ 2");
        assertTrue(optional.host().getChosenCards().isEmpty());
        assertNull(optional.actor().getController().chooseSingleEntityForEffect(optional.actor().getOpponents(), null, "optional", true, null));
        assertTrue(optional.actor().getController().chooseEntitiesForEffect(new FCollection<>(List.of(optional.first())), 0, 1, null, null, "up to one", null, null).isEmpty());

        Fixture partial = fixture(request -> List.of(0));
        assertThrows(IllegalArgumentException.class, () -> partial.actor().getController().chooseCardsForEffect(
                new CardCollection(List.of(partial.first(), partial.second())), null, "optional pair", 2, 2, true, null));

        Fixture shortList = fixture(envelope -> {
            Map<?, ?> request = (Map<?, ?>) envelope.get("options");
            assertEquals(request.get("min"), 1);
            assertEquals(request.get("max"), 1);
            return List.of(0);
        });
        var chosen = shortList.actor().getController().chooseCardsForEffect(new CardCollection(shortList.first()), null, "available", 3, 4, false, null);
        assertSame(chosen.getFirst(), shortList.first());
        Fixture empty = fixture(envelope -> {
            Map<?, ?> request = (Map<?, ?>) envelope.get("options");
            assertEquals(request.get("min"), 0);
            assertEquals(request.get("max"), 0);
            return List.of();
        });
        assertNull(empty.actor().getController().chooseSingleEntityForEffect(new FCollection<Card>(), null, "empty", false, null));
    }

    @Test public void rejectsUnimplementedVisibilityBeforeCallingClient() {
        for (String variant : List.of("library", "opponent_hand", "face_down", "reveal", "params")) {
            Fixture fixture = fixture(request -> { throw new AssertionError("Unsupported context reached client"); });
            Card candidate = switch (variant) {
                case "library" -> fixture.actor().getCardsIn(ZoneType.Library).getFirst();
                case "opponent_hand" -> fixture.opponent().getCardsIn(ZoneType.Hand).getFirst();
                default -> fixture.first();
            };
            if (variant.equals("face_down")) { candidate.setFaceDown(true); }
            DelayedReveal reveal = variant.equals("reveal")
                    ? new DelayedReveal(new CardCollection(candidate), ZoneType.Battlefield, fixture.actor().getView(), "reveal") : null;
            Map<String, Object> params = variant.equals("params") ? Map.of("unsupported", true) : null;
            assertThrows(UnsupportedOperationException.class, () -> fixture.actor().getController().chooseSingleEntityForEffect(
                    new FCollection<>(List.of(candidate)), reveal, null, "choose", false, null, params));
            assertThrows(UnsupportedOperationException.class, () -> fixture.actor().getController().chooseNumber(null, "stopped", 0, 1));
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toRealPath();
        for (Class<?> type : List.of(AbilityFactory.class, AbilityUtils.class, Game.class)) {
            assertEquals(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath(),
                    root.resolve("forge-game/target/classes").toRealPath());
        }
        OriginalCompositeChoice.initialize(root);
        var test = new NativeDecisionOptionsTest();
        test.effectsSupplyFilteredAndCurrentOptions();
        test.validatesSelectionsAndOptionalDeclines();
        test.rejectsUnimplementedVisibilityBeforeCallingClient();
        System.out.println("PASS: independent RL selections use original effect options; execution, constraints, refusal and visibility guards verified");
    }
}
