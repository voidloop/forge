package forge.rl;

import forge.StaticData;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class PilotChoicesTest {
    private record Position(Game game, Player actor, Player opponent) {
        Card add(Player owner, ZoneType zone, String name) {
            var card = CardFactory.getCard(StaticData.instance().getCommonCards().getCard(name), owner, game);
            owner.getZone(zone).add(card);
            return card;
        }

        List<SpellAbility> choose() {
            return actor.getController().chooseSpellAbilityToPlay();
        }
    }

    @BeforeClass
    public void initialize() { new GameStateReaderTest().initialize(); }

    private Position position(IPilotChoices chooser) {
        var lobby = new LobbyPlayerPilot("first", chooser);
        lobby.setAiProfile("Default");
        var players = List.of(new RegisteredPlayer(new Deck("a")).setPlayer(lobby),
                new RegisteredPlayer(new Deck("b")).setPlayer(new LobbyPlayerAi("second", null)));
        var game = new Match(new GameRules(GameType.Constructed), players, "priority choices").createGame();
        game.getRegisteredPlayers().forEach(player -> player.setLife(20, null));
        game.setAge(GameStage.Play);
        var actor = game.getRegisteredPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, actor, false, 6);
        return new Position(game, actor, game.getRegisteredPlayers().get(1));
    }

    private Object ref(Map<String, Object> snapshot, String name) {
        for (Object row : (List<?>) snapshot.get("cards")) {
            Map<?, ?> card = (Map<?, ?>) row;
            if (name.equals(card.get("name"))) return card.get("id");
        }
        throw new AssertionError("Missing visible card " + name);
    }

    private int source(List<Map<String, Object>> actions, Object id) {
        for (int i = 0; i < actions.size(); i++) {
            if (id.equals(actions.get(i).get("source"))) return i;
        }
        return -1;
    }

    @Test
    public void duplicateLandsRemainDistinctAndAnAuraCanEnchantAnEnemyCreature() {
        var position = position((stage, state, actions) -> {
            if (stage.equals("target")) {
                assertEquals(actions.size(), 1);
                assertEquals(actions.get(0).get("ref"), ref(state, "Havoc Devils"));
                return 0;
            }
            var lands = actions.stream().filter(action -> "land".equals(action.get("kind"))).toList();
            assertEquals(lands.size(), 2);
            assertNotEquals(lands.get(0).get("source"), lands.get(1).get("source"));
            return source(actions, ref(state, "Blanchwood Armor"));
        });
        var me = position.actor();
        position.add(me, ZoneType.Hand, "Forest");
        position.add(me, ZoneType.Hand, "Forest");
        position.add(me, ZoneType.Hand, "Blanchwood Armor");
        for (int i = 0; i < 3; i++) position.add(me, ZoneType.Battlefield, "Forest");
        Card enemy = position.add(position.opponent(), ZoneType.Battlefield, "Havoc Devils");
        var spell = position.choose().get(0);
        assertSame(spell.getTargets().getFirstTargetedCard(), enemy);
        me.getController().playChosenSpellAbility(spell);
        position.game().getStack().resolveStack();
        assertTrue(me.getCardsIn(ZoneType.Battlefield).stream().anyMatch(c -> c.getName().equals("Blanchwood Armor")));
    }

    @Test
    public void ghaltaUsesReducedCostAndOnlyReadyDorksCanTapForMana() {
        List<Boolean> offered = new ArrayList<>();
        var position = position((stage, state, actions) -> {
            assertEquals(stage, "priority");
            assertTrue(source(actions, ref(state, "Forest")) >= 0);
            assertEquals(source(actions, ref(state, "Llanowar Elves")), -1);
            offered.add(source(actions, ref(state, "Ghalta, Primal Hunger")) >= 0);
            assertEquals(source(actions, ref(state, "Druid of the Cowl")) >= 0, offered.get(offered.size() - 1).booleanValue());
            return actions.indexOf(Map.of("kind", "pass"));
        });
        var me = position.actor();
        position.add(me, ZoneType.Hand, "Ghalta, Primal Hunger");
        position.add(me, ZoneType.Battlefield, "Gigantosaurus");
        position.add(me, ZoneType.Battlefield, "Forest");
        position.add(me, ZoneType.Battlefield, "Llanowar Elves").setSickness(true);
        var druid = position.add(me, ZoneType.Battlefield, "Druid of the Cowl");
        druid.setSickness(true);
        assertNull(position.choose());
        druid.setSickness(false);
        var reader = new GameStateReader(position.game(), me);
        var before = reader.snapshot();
        assertNull(position.choose());
        assertEquals(reader.snapshot(), before);
        assertEquals(offered, List.of(false, true));
    }

    @Test
    public void rabidBiteNeedsBothTargetsAndOffersThemInOrder() {
        var stages = new ArrayList<String>();
        var position = position((stage, state, actions) -> {
            stages.add(stage);
            if (stage.equals("priority")) {
                int spell = source(actions, ref(state, "Rabid Bite"));
                if (stages.size() == 1) {
                    assertEquals(spell, -1);
                    return actions.indexOf(Map.of("kind", "pass"));
                }
                assertTrue(spell >= 0);
                return spell;
            }
            String name = stages.size() == 3 ? "Vigilant Baloth" : "Havoc Devils";
            assertEquals(actions.size(), 1);
            assertEquals(actions.get(0).get("ref"), ref(state, name));
            return 0;
        });
        var me = position.actor();
        position.add(me, ZoneType.Battlefield, "Forest");
        position.add(me, ZoneType.Battlefield, "Forest");
        Card friendly = position.add(me, ZoneType.Battlefield, "Vigilant Baloth");
        position.add(me, ZoneType.Hand, "Rabid Bite");
        assertNull(position.choose());
        Card enemy = position.add(position.opponent(), ZoneType.Battlefield, "Havoc Devils");
        var selected = position.choose().get(0);
        assertEquals(stages, List.of("priority", "priority", "target", "target"));
        assertSame(selected.getTargets().getFirstTargetedCard(), friendly);
        assertSame(selected.getSubAbility().getTargets().getFirstTargetedCard(), enemy);
        me.getController().playChosenSpellAbility(selected);
        assertSame(position.game().getStack().peekAbility(), selected);
    }

    @Test
    public void shockCanTargetEitherPlayerEvenWhenLathlissCanActivate() {
        var position = position((stage, state, actions) -> {
            if (stage.equals("priority")) {
                int pump = source(actions, ref(state, "Lathliss, Dragon Queen"));
                assertTrue(pump >= 0);
                assertEquals(actions.get(pump).get("kind"), "ability");
                return source(actions, ref(state, "Shock"));
            }
            assertEquals(stage, "target");
            assertTrue(actions.contains(Map.of("kind", "player", "ref", "p1")));
            assertTrue(actions.contains(Map.of("kind", "card", "ref", ref(state, "Lathliss, Dragon Queen"))));
            return actions.indexOf(Map.of("kind", "player", "ref", "p0"));
        });
        var me = position.actor();
        position.add(me, ZoneType.Battlefield, "Mountain");
        position.add(me, ZoneType.Battlefield, "Mountain");
        position.add(me, ZoneType.Battlefield, "Lathliss, Dragon Queen");
        position.add(me, ZoneType.Hand, "Shock");
        var spell = position.choose().get(0);
        me.getController().playChosenSpellAbility(spell);
        position.game().getStack().resolveStack();
        assertEquals(me.getLife(), 18);
        assertEquals(position.opponent().getLife(), 20);
    }
}
