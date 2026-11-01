package forge.rl;

import forge.ai.LobbyPlayerAi;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

import static org.testng.Assert.*;

public class PlayerReaderTest {
    @BeforeClass public void initialize() { OriginalCompositeChoice.initialize(Path.of("..")); }

    @Test public void manaObservationIsSilentAndPreservesNativeContextAndRandomness() throws Exception {
        var f = ManaPaymentBoundaryTest.fixture(new LobbyPlayerAi("first", null), false);
        Player opponent = f.game().getRegisteredPlayers().get(1);
        var root = f.lands().get(0).getManaAbilities().get(0);
        var child = (AbilitySub) AbilityFactory.getAbility(
                "DB$ Mana | Produced$ U | ConditionPlayerTurn$ True", root.getHostCard());
        root.setSubAbility(child);
        root.setActivatingPlayer(null);
        child.setActivatingPlayer(opponent);
        var extra = AbilityFactory.getAbility("DB$ Mana | Produced$ R", root.getHostCard());
        root.setAdditionalAbility("unused", extra);
        var listed = (AbilitySub) AbilityFactory.getAbility("DB$ Mana | Produced$ W", root.getHostCard());
        root.setAdditionalAbilityList("unused", List.of(listed));
        listed.setActivatingPlayer(f.player());
        f.lands().get(1).getManaAbilities().get(0).setActivatingPlayer(opponent);
        for (String name : List.of("Simic Guildgate", "Wastes")) {
            Card land = OriginalCompositeChoice.add(f.game(), f.player(), name);
            f.player().getZone(ZoneType.Hand).remove(land);
            f.player().getZone(ZoneType.Battlefield).add(land);
        }
        var facts = OriginalCompositeChoice.observe(f.game());
        String rng = FullGameTest.randomState();
        var view = new StateReader(f.player(), 17L);
        PrintStream previous = System.out;
        var bytes = new ByteArrayOutputStream();
        try (var captured = new PrintStream(bytes)) {
            System.setOut(captured);
            for (int i = 0; i < 2; i++) {
                var snapshot = PlayerReader.read(f.player(), view, 0);
                assertEquals(snapshot.get("available_mana_total"), 6);
                assertEquals(snapshot.get("available_mana"), List.of(List.of("black", 0), List.of("blue", 2),
                        List.of("colorless", 1), List.of("green", 3), List.of("red", 1), List.of("white", 0)));
            }
        } finally { System.setOut(previous); view.close(); }
        assertEquals(bytes.toString(), "", "Observation must not emit a missing-activator diagnostic");
        assertNull(root.getActivatingPlayer());
        assertSame(child.getActivatingPlayer(), opponent);
        assertNull(extra.getActivatingPlayer());
        assertSame(listed.getActivatingPlayer(), f.player());
        assertSame(f.lands().get(1).getManaAbilities().get(0).getActivatingPlayer(), opponent);
        assertEquals(OriginalCompositeChoice.observe(f.game()), facts);
        assertEquals(FullGameTest.randomState(), rng);
    }

    @Test public void failedManaProbeRestoresEveryActivatorAndPropagates() {
        var f = ManaPaymentBoundaryTest.fixture(new LobbyPlayerAi("first", null), false);
        Player opponent = f.game().getRegisteredPlayers().get(1);
        Card land = f.lands().get(0);
        var failing = new SpellAbility.EmptySa(land, (Player) null) {
            @Override public boolean isManaAbility() { return true; }
            @Override public boolean canProduce(String symbol) {
                assertSame(getActivatingPlayer(), f.player());
                assertSame(getSubAbility().getActivatingPlayer(), opponent);
                throw new IllegalStateException("Mana probe failed");
            }
        };
        var child = (AbilitySub) AbilityFactory.getAbility("DB$ Mana | Produced$ U", land);
        failing.setSubAbility(child);
        child.setActivatingPlayer(opponent);
        land.addSpellAbility(failing, false);
        var view = new StateReader(f.player(), 17L);
        try {
            var error = expectThrows(IllegalStateException.class, () -> PlayerReader.read(f.player(), view, 0));
            assertEquals(error.getMessage(), "Mana probe failed");
            assertNull(failing.getActivatingPlayer());
            assertSame(child.getActivatingPlayer(), opponent);
            assertNull(land.getManaAbilities().get(0).getActivatingPlayer());
        } finally { view.close(); }
    }
}
