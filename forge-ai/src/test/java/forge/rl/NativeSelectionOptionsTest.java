package forge.rl;

import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollectionView;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.testng.Assert.*;

/** Experiment only: receive original effect arguments, without implementing RL selections. */
public class NativeSelectionOptionsTest {
    private record Request(String kind, List<String> options, int min, int max, boolean optional) { }

    // AI is only the fixture scaffold. The three selection overrides never call it.
    private static final class SelectionController extends PlayerControllerAi {
        final List<Request> requests = new ArrayList<>();

        SelectionController(Game game, Player player, LobbyPlayerAi lobby) { super(game, player, lobby); }

        private <T extends GameEntity> List<T> select(String kind, Iterable<T> options, int min, int max, boolean optional) {
            List<T> bindings = new ArrayList<>();
            options.forEach(bindings::add);
            Request request = new Request(kind, bindings.stream().map(GameEntity::getName).toList(), min, max, optional);
            requests.add(request);
            System.out.println("NATIVE OPTIONS " + request);
            // The scripted client sees labels only; indices bind to this invocation's native list.
            List<Integer> answer = kind.equals("cards") ? List.of(1, 0) : List.of(0);
            assertTrue(answer.size() >= min && answer.size() <= max);
            assertEquals(new HashSet<>(answer).size(), answer.size());
            assertTrue(answer.stream().allMatch(index -> index >= 0 && index < bindings.size()));
            return answer.stream().map(bindings::get).toList();
        }

        @Override public <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> options,
                DelayedReveal reveal, SpellAbility sa, String title, boolean optional, Player related, Map<String, Object> params) {
            assertNull(reveal);
            return select("single", options, optional ? 0 : 1, 1, optional).get(0);
        }

        @Override public CardCollectionView chooseCardsForEffect(CardCollectionView options, SpellAbility sa,
                String title, int min, int max, boolean optional, Map<String, Object> params) {
            return new CardCollection(select("cards", options, min, max, optional));
        }

        @Override public <T extends GameEntity> List<T> chooseEntitiesForEffect(FCollectionView<T> options,
                int min, int max, DelayedReveal reveal, SpellAbility sa, String title, Player related, Map<String, Object> params) {
            assertNull(reveal);
            return select("entities", options, min, max, false);
        }
    }

    private static final class SelectionLobby extends LobbyPlayerAi {
        SelectionLobby() { super("first", null); }
        @Override public Player createIngamePlayer(Game game, int id) {
            Player player = new Player(getName(), game, id);
            player.setFirstController(new SelectionController(game, player, this));
            return player;
        }
    }

    @BeforeClass public void initialize() { OriginalCompositeChoice.initialize(Path.of("..")); }

    private static void resolve(Card host, Player player, String script) {
        SpellAbility ability = AbilityFactory.getAbility(script, host);
        ability.setActivatingPlayer(player);
        AbilityUtils.resolve(ability);
    }

    @Test public void effectsSupplyFilteredAndCurrentOptions() {
        var seats = List.of(
                new RegisteredPlayer(new Deck("first")).setPlayer(new SelectionLobby()),
                new RegisteredPlayer(new Deck("second")).setPlayer(new LobbyPlayerAi("second", null)));
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
        SelectionController controller = (SelectionController) actor.getController();

        resolve(host, actor, "DB$ ChoosePlayer | Defined$ You | Choices$ Player.Opponent | DontNotify$ True");
        assertSame(host.getChosenPlayer(), opponent);
        assertEquals(controller.requests.get(0), new Request("single", List.of("second"), 1, 1, false));

        resolve(host, actor, "DB$ ChooseCard | Defined$ You | ChoiceZone$ Battlefield | Choices$ Creature.YouCtrl | Amount$ 2 | Mandatory$ True");
        assertEquals(controller.requests.get(1), new Request("cards", List.of("Grizzly Bears", "Runeclaw Bear"), 2, 2, false));
        assertEquals(new ArrayList<>(host.getChosenCards()), List.of(second, first));

        String tap = "DB$ Tap | CardChoices$ Creature.YouCtrl+untapped | ChoiceAmount$ 1";
        resolve(host, actor, tap);
        assertTrue(first.isTapped());
        assertFalse(second.isTapped());
        assertFalse(host.isTapped());
        resolve(host, actor, tap);
        assertTrue(second.isTapped());
        assertEquals(controller.requests.subList(2, 4), List.of(
                new Request("entities", List.of("Grizzly Bears", "Runeclaw Bear"), 1, 1, false),
                new Request("entities", List.of("Runeclaw Bear"), 1, 1, false)));
        assertEquals(controller.requests.size(), 4);
    }

    private static Card add(Game game, Player player, String name, ZoneType zone) {
        Card card = OriginalCompositeChoice.add(game, player, name);
        if (zone != ZoneType.Hand) { card = game.getAction().moveTo(zone, card, null, null); }
        return card;
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toRealPath();
        for (Class<?> type : List.of(AbilityFactory.class, AbilityUtils.class, Game.class)) {
            assertEquals(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath(),
                    root.resolve("forge-game/target/classes").toRealPath());
        }
        OriginalCompositeChoice.initialize(root);
        new NativeSelectionOptionsTest().effectsSupplyFilteredAndCurrentOptions();
        System.out.println("PASS: original effects supply filtered current options and constraints; local responses applied without AI selection");
    }
}
