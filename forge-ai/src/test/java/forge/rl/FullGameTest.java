package forge.rl;

import com.google.common.eventbus.Subscribe;
import forge.LobbyPlayer;
import forge.MulliganDefs;
import forge.StaticData;
import forge.ai.LobbyPlayerAi;
import forge.game.*;
import forge.game.event.*;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.deck.Deck;
import forge.util.MyRandom;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.*;
import java.nio.file.Path;
import java.util.*;

import static org.testng.Assert.*;

/** One ordinary deck and natural start-to-outcome loop; private facts never enter a request. */
public class FullGameTest {
    public static final List<String> CREATURES = List.of("Grizzly Bears", "Runeclaw Bear", "Centaur Courser",
            "Nessian Courser", "Craw Wurm", "Kalonian Tusker", "Rumbling Baloth", "Silverback Ape");

    @BeforeClass public void initialize() { OriginalCompositeChoice.initialize(Path.of("..")); }

    public static Game fixture(List<LobbyPlayer> lobbies) {
        StaticData.instance().setFilteredHandsEnabled(false);
        StaticData.instance().setMulliganRule(MulliganDefs.MulliganRule.London);
        List<RegisteredPlayer> seats = new ArrayList<>();
        for (LobbyPlayer lobby : lobbies) {
            Deck deck = new Deck("vanilla duel");
            deck.getMain().add(StaticData.instance().getCommonCards().getCard("Forest"), 28);
            for (String name : CREATURES) {
                deck.getMain().add(StaticData.instance().getCommonCards().getCard(name), 4);
            }
            seats.add(new RegisteredPlayer(deck).setPlayer(lobby));
        }
        return new Match(new GameRules(GameType.Constructed), seats, "full game").createGame();
    }

    public static final class Trace {
        public final List<String> events = new ArrayList<>();
        public RuntimeException failure;
        private final int limit;
        public Trace(int limit) { this.limit = limit; }
        @Subscribe public void event(GameEvent event) {
            if (events.size() >= limit) { failure = new IllegalStateException("Full-game event limit exceeded"); }
            if (event instanceof GameEventGameStarted || event instanceof GameEventTurnBegan
                    || event instanceof GameEventPlayerPriority || event instanceof GameEventSpellResolved
                    || event instanceof GameEventAttackersDeclared || event instanceof GameEventBlockersDeclared
                    || event instanceof GameEventPlayerDamaged || event instanceof GameEventCardDamaged
                    || event instanceof GameEventGameOutcome || event instanceof GameEventGameFinished) {
                if (failure == null) {
                    String text = event.getClass().getSimpleName() + ": " + event;
                    events.add(text);
                    System.out.println(text);
                }
            }
        }
        public void check() { if (failure != null) { throw failure; } }
    }

    public static String randomState() throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var stream = new ObjectOutputStream(bytes)) { stream.writeObject(MyRandom.getRandom()); }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    public static void restoreRandom(String state) throws IOException, ClassNotFoundException {
        // Only trusted, locally generated experiment metadata, never a model response.
        try (var stream = new ObjectInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(state)))) {
            MyRandom.setRandom((Random) stream.readObject());
        }
    }

    public static List<Object> facts(Game game) {
        List<Object> result = new ArrayList<>();
        result.add(List.of(game.getPhaseHandler().getTurn(), String.valueOf(game.getPhaseHandler().getPhase()),
                game.getRegisteredPlayers().indexOf(game.getStartingPlayer()), game.isGameOver()));
        for (Player p : game.getRegisteredPlayers()) {
            List<Object> zones = new ArrayList<>();
            for (ZoneType zone : List.of(ZoneType.Hand, ZoneType.Library, ZoneType.Battlefield,
                    ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command, ZoneType.Stack)) {
                zones.add(List.of(zone.toString(), p.getCardsIn(zone).stream().map(c -> List.of(
                        c.getId(), c.getName(), c.isTapped(), c.getDamage(), c.getNetPower(), c.getNetToughness(),
                        game.getRegisteredPlayers().indexOf(c.getController()))).toList()));
            }
            result.add(List.of(p.getLife(), zones, p.getManaPool().totalMana(), String.valueOf(p.getOutcome())));
        }
        if (game.getOutcome() != null) {
            result.add(List.of(game.getOutcome().getWinCondition().toString(), game.getOutcome().isDraw(),
                    game.getOutcome().getOutcomeStrings()));
        }
        return result;
    }

    @Test public void emptyUnsupportedReportIsAServiceAndRealUnsupportedCardsStop() {
        Game game = fixture(List.of(new LobbyPlayerRl("first", request -> { throw new AssertionError("No question expected"); }),
                new LobbyPlayerAi("second", null)));
        var controller = (PlayerControllerRl) game.getRegisteredPlayers().get(0).getController();
        Player player = game.getRegisteredPlayers().get(0);
        try {
            controller.revealUnsupported(Map.of(player, List.of()));
            assertThrows(UnsupportedOperationException.class, () -> controller.revealUnsupported(Map.of(player,
                    List.of(StaticData.instance().getCommonCards().getCard("Forest")))));
            assertThrows(UnsupportedOperationException.class, controller::autoPassCancel);
            assertFalse(game.isGameOver());
        } finally { controller.closeObservation(); }
    }
}
