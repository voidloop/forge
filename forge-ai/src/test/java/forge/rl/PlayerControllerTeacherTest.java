package forge.rl;

import forge.StaticData;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
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
import forge.util.MyRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

/** Teacher labels describe the original choice before native execution. */
public class PlayerControllerTeacherTest {
    private record Fixture(Game game, Player player, PlayerControllerAi controller) { }

    @BeforeClass
    public void initialize() { new GameStateReaderTest().initialize(); }

    private Fixture fixture(ITeacherRecorder sink) {
        MyRandom.setRandom(new Random(42));
        var lobby = new LobbyPlayerTeacher("first", sink);
        lobby.setAiProfile("Default");
        var registered = List.of(
                new RegisteredPlayer(new Deck("first")).setPlayer(lobby),
                new RegisteredPlayer(new Deck("second")).setPlayer(new LobbyPlayerAi("second", null)));
        Game game = new Match(new GameRules(GameType.Constructed), registered, "teacher test").createGame();
        game.getRegisteredPlayers().forEach(player -> player.setLife(20, null));
        game.setAge(GameStage.Play);
        Player player = game.getRegisteredPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, player, false, 6);
        return new Fixture(game, player, (PlayerControllerAi) player.getController());
    }

    private Card add(Fixture fixture, ZoneType zone, String name) {
        Card card = CardFactory.getCard(StaticData.instance().getCommonCards().getCard(name), fixture.player(), fixture.game());
        fixture.player().getZone(zone).add(card);
        return card;
    }

    private void lands(Fixture fixture, int count) {
        for (int i = 0; i < count; i++) { add(fixture, ZoneType.Battlefield, "Forest"); }
    }

    @Test
    public void recordsPreChoiceStateAndPreservesOriginalSpellForNativeExecution() {
        List<Map<String, Object>> examples = new ArrayList<>();
        Fixture teacher = fixture(examples::add);
        lands(teacher, 5);
        add(teacher, ZoneType.Hand, "Highland Game");
        Card largest = add(teacher, ZoneType.Hand, "Gigantosaurus");
        Map<String, Object> before = new GameStateReader(teacher.game(), teacher.player()).snapshot();
        List<SpellAbility> chosen = teacher.controller().chooseSpellAbilityToPlay();
        assertSame(chosen.get(0), largest.getFirstSpellAbility());
        assertEquals(examples.size(), 1);
        Map<String, Object> example = examples.get(0);
        assertEquals(example.keySet(), Set.of("observation", "choice"));
        assertEquals(example.get("observation"), before);
        List<?> choices = (List<?>) example.get("choice");
        assertEquals(choices.size(), 1);
        Map<?, ?> choice = (Map<?, ?>) choices.get(0);
        Map<?, ?> observation = (Map<?, ?>) example.get("observation");
        Map<?, ?> source = ((List<?>) observation.get("cards")).stream().map(row -> (Map<?, ?>) row)
                .filter(row -> row.get("id").equals(choice.get("source"))).findFirst().orElseThrow();
        assertEquals(source.get("name"), "Gigantosaurus");
        assertFalse(source.containsKey("abilities"));
        assertEquals(choice.get("kind"), "spell");
        assertEquals(choice.keySet(), Set.of("source", "kind"));
        assertTrue(largest.isInZone(ZoneType.Hand));
        assertTrue(teacher.game().getStack().isEmpty());
        assertTrue(teacher.controller().playChosenSpellAbility(chosen.get(0)));
        assertSame(teacher.game().getStack().peekAbility(), chosen.get(0));
        assertEquals(examples.size(), 1);
        assertEquals(example.get("observation"), before, "Execution must not mutate the saved state");
    }

    @Test
    public void recordsOriginalLandChoiceWithoutSpellWindowFiltering() {
        List<Map<String, Object>> examples = new ArrayList<>();
        Fixture teacher = fixture(examples::add);
        Card forest = add(teacher, ZoneType.Hand, "Forest");
        SpellAbility land = teacher.controller().chooseSpellAbilityToPlay().get(0);
        assertTrue(land.isLandAbility());
        assertEquals(examples.size(), 1);
        Map<?, ?> choice = (Map<?, ?>) ((List<?>) examples.get(0).get("choice")).get(0);
        Map<?, ?> observation = (Map<?, ?>) examples.get(0).get("observation");
        Map<?, ?> source = ((List<?>) observation.get("cards")).stream().map(row -> (Map<?, ?>) row)
                .filter(row -> row.get("id").equals(choice.get("source"))).findFirst().orElseThrow();
        assertEquals(source.get("name"), "Forest");
        assertEquals(choice.get("kind"), "land");
        assertTrue(teacher.controller().playChosenSpellAbility(land));
        assertTrue(forest.isInPlay());
    }

    @Test
    public void recordsNativePassAsAnExplicitAction() {
        List<Map<String, Object>> examples = new ArrayList<>();
        Fixture teacher = fixture(examples::add);
        assertNull(teacher.controller().chooseSpellAbilityToPlay());
        assertEquals(examples.size(), 1);
        assertEquals(examples.get(0).get("choice"), List.of(Map.of("kind", "pass")));
    }

}
