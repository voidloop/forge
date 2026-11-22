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

    private Position position(IPriorityChoices chooser) {
        var lobby = new LobbyPlayerRl("first", chooser);
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
    public void priorityMenuUsesNativeCandidatesAndPreservesCopies() {
        var position = position((state, actions) -> {
            var lands = actions.stream().filter(a -> "land".equals(a.get("kind"))).toList();
            assertEquals(lands.size(), 2);
            assertNotEquals(lands.get(0).get("source"), lands.get(1).get("source"));
            assertTrue(source(actions, ref(state, "Ghalta, Primal Hunger")) >= 0);
            assertEquals(source(actions, ref(state, "Llanowar Elves")), -1);
            assertTrue(source(actions, ref(state, "Druid of the Cowl")) >= 0);
            return actions.indexOf(Map.of("kind", "pass"));
        });
        var me = position.actor();
        position.add(me, ZoneType.Hand, "Forest");
        position.add(me, ZoneType.Hand, "Forest");
        // Native candidates may still need more mana than is available.
        position.add(me, ZoneType.Hand, "Ghalta, Primal Hunger");
        position.add(me, ZoneType.Battlefield, "Llanowar Elves").setSickness(true);
        position.add(me, ZoneType.Battlefield, "Druid of the Cowl").setSickness(false);
        assertNull(position.choose());
    }

    @Test
    public void rabidBiteUsesTheInheritedAiForBothTargets() {
        var position = position((state, actions) -> source(actions, ref(state, "Rabid Bite")));
        var me = position.actor();
        position.add(me, ZoneType.Battlefield, "Forest");
        position.add(me, ZoneType.Battlefield, "Forest");
        Card friendly = position.add(me, ZoneType.Battlefield, "Vigilant Baloth");
        Card enemy = position.add(position.opponent(), ZoneType.Battlefield, "Havoc Devils");
        position.add(me, ZoneType.Hand, "Rabid Bite");
        var selected = position.choose().get(0);
        assertSame(selected.getTargets().getFirstTargetedCard(), friendly);
        assertSame(selected.getSubAbility().getTargets().getFirstTargetedCard(), enemy);
        me.getController().playChosenSpellAbility(selected);
        assertSame(position.game().getStack().peekAbility(), selected);
    }

    @Test
    public void shockUsesInheritedTargetingPaymentAndResolution() {
        var position = position((state, actions) -> source(actions, ref(state, "Shock")));
        var me = position.actor();
        var mountain = position.add(me, ZoneType.Battlefield, "Mountain");
        position.add(me, ZoneType.Hand, "Shock");
        var spell = position.choose().get(0);
        assertSame(spell.getTargets().getFirstTargetedPlayer(), position.opponent());
        me.getController().playChosenSpellAbility(spell);
        assertTrue(mountain.isTapped());
        position.game().getStack().resolveStack();
        assertEquals(position.opponent().getLife(), 18);
        assertEquals(me.getLife(), 20);
    }

    @Test
    public void missingTargetsLeavePriorityWithTheGym() {
        var offered = new ArrayList<Boolean>();
        var position = position((state, actions) -> {
            int plummet = source(actions, ref(state, "Plummet"));
            offered.add(plummet >= 0);
            if (offered.size() == 1) return plummet;
            return actions.indexOf(Map.of("kind", "pass"));
        });
        var me = position.actor();
        position.add(me, ZoneType.Hand, "Plummet");
        position.add(me, ZoneType.Battlefield, "Forest");
        position.add(me, ZoneType.Battlefield, "Forest");
        assertNull(position.choose());
        assertEquals(offered, List.of(true, false));
    }
}
