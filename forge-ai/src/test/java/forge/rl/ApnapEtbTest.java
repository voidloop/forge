package forge.rl;

import com.google.common.eventbus.Subscribe;
import forge.LobbyPlayer;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.event.*;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.*;

import static org.testng.Assert.*;

public class ApnapEtbTest {
    @BeforeClass public void initialize() { OriginalCompositeChoice.initialize(Path.of("..")); }

    private record Fixture(ManaPaymentBoundaryTest.Fixture base, List<Card> entrants, List<Integer> counts, int active) { }

    private static Card place(Player player, String name, ZoneType zone) {
        Card card = OriginalCompositeChoice.add(player.getGame(), player, name);
        player.getZone(ZoneType.Hand).remove(card);
        player.getZone(zone).add(card);
        return card;
    }

    private static Fixture fixture(LobbyPlayer lobby, List<Integer> counts, int active) {
        var f = ManaPaymentBoundaryTest.fixture(lobby, false);
        f.lands().forEach(card -> f.player().getZone(ZoneType.Battlefield).remove(card));
        var players = f.game().getPlayers();
        var entrants = new ArrayList<Card>();
        for (int seat = 0; seat < 2; seat++) {
            Player player = players.get(seat);
            if (counts.get(seat) == 2) { place(player, "Hinterland Sanctifier", ZoneType.Battlefield); }
            entrants.add(place(player, "Helpful Hunter", ZoneType.Exile));
            place(player, seat == 0 ? "Island" : "Swamp", ZoneType.Library);
            for (int i = 0; i < 7; i++) { place(player, "Mountain", ZoneType.Library); }
            player.setLandsPlayedThisTurn(1);
        }
        Player opponent = players.get(1);
        opponent.dangerouslySetController(new PlayerControllerAi(f.game(), opponent, new LobbyPlayerAi("second", null)));
        var phase = f.game().getPhaseHandler();
        phase.devModeSet(PhaseType.MAIN2, players.get(active), false, 6);
        phase.setPriority(players.get(active));
        f.game().getStack().addAndUnfreeze(f.spell());
        return new Fixture(f, entrants, counts, active);
    }

    private static List<SpellAbility> stack(Fixture f) {
        var result = new ArrayList<SpellAbility>();
        f.base().game().getStack().forEach(entry -> result.add(entry.getSpellAbility()));
        return List.copyOf(result);
    }

    private static void enter(Fixture f) {
        var game = f.base().game();
        game.getStack().freezeStack(null);
        // Queue both public ETBs before the same native priority boundary, in reverse seat order.
        for (int seat : List.of(1, 0)) {
            game.getAction().moveToPlay(f.entrants().get(seat), null, AbilityKey.newMap());
        }
        game.getStack().unfreezeStack();
    }

    public static final class Events {
        final List<String> trace = new ArrayList<>();
        @Subscribe public void moved(GameEventCardChangeZone e) {
            trace.add("move:" + e.card().getName() + ":" + e.card().getOwner().getName() + ":" + e.from().zoneType() + ":" + e.to().zoneType());
        }
        @Subscribe public void life(GameEventPlayerLivesChanged e) {
            trace.add("life:" + e.player().getName() + ":" + e.oldLives() + ":" + e.newLives());
        }
        @Subscribe public void resolved(GameEventSpellResolved e) { trace.add("resolved:" + e.stackDescription() + ":" + e.hasFizzled()); }
        @Subscribe public void priority(GameEventPlayerPriority e) { trace.add("priority:" + e.priority().getName()); }
    }

    private record Outcome(List<Object> facts, List<List<Integer>> intermediate, List<String> events, long rng) { }

    private static Outcome run(Fixture f) {
        var game = f.base().game();
        var phase = game.getPhaseHandler();
        var players = game.getPlayers();
        var events = new Events();
        game.subscribeToEvents(events);
        enter(f);
        phase.onStackResolved();
        phase.mainLoopStep();
        int total = f.counts().stream().mapToInt(Integer::intValue).sum();
        var prepared = stack(f);
        assertEquals(prepared.size(), total + 1);
        assertSame(prepared.get(total), f.base().spell());
        var intermediate = new ArrayList<List<Integer>>();
        for (int i = 0; i < total; i++) {
            int seat = i < f.counts().get(1 - f.active()) ? 1 - f.active() : f.active();
            assertSame(game.getStack().peekAbility(), prepared.get(i));
            assertSame(prepared.get(i).getActivatingPlayer(), players.get(seat));
            phase.mainLoopStep();
            assertEquals(game.getStack().size(), total - i);
            assertFalse(game.getStack().isResolving());
            intermediate.add(players.stream().flatMap(p -> List.of(p.getLife(), p.getCardsIn(ZoneType.Hand).size()).stream()).toList());
            phase.mainLoopStep();
        }
        assertSame(game.getStack().peekAbility(), f.base().spell());
        assertSame(phase.getPriorityPlayer(), players.get(1 - f.active()));
        for (int seat = 0; seat < 2; seat++) {
            Player player = players.get(seat);
            assertEquals(player.getLife(), 19 + f.counts().get(seat));
            assertEquals(player.getCardsIn(ZoneType.Hand).stream().map(Card::getName).toList(), List.of(seat == 0 ? "Island" : "Swamp"));
            assertEquals(player.getCardsIn(ZoneType.Library).size(), 7);
            assertTrue(player.getManaPool().isEmpty());
        }
        assertNull(phase.getPriorityPlayer().getController().chooseSpellAbilityToPlay());
        assertEquals(phase.getPhase(), PhaseType.MAIN2);
        assertEquals(phase.getTurn(), 6);
        assertFalse(game.isGameOver());
        return new Outcome(OriginalCompositeChoice.observe(game), intermediate, List.copyOf(events.trace), MyRandom.getRandom().nextLong());
    }

    private static final class Probe extends PlayerControllerAi {
        final Fixture fixture;
        final List<String> calls;
        List<SpellAbility> before;
        List<SpellAbility> inserted;
        int resolved;
        Probe(Fixture f, Player player, List<String> calls) {
            super(player.getGame(), player, new LobbyPlayerAi(player.getName(), null));
            fixture = f; this.calls = calls;
        }
        @Override public void orderAndPlaySimultaneousSa(List<SpellAbility> abilities) {
            calls.add("prepare:" + getGame().getPlayers().indexOf(player));
            before = List.copyOf(abilities);
            var tail = stack(fixture);
            assertTrue(before.stream().allMatch(sa -> sa.getActivatingPlayer() == player && sa.getHostCard().getController() == player));
            super.orderAndPlaySimultaneousSa(abilities);
            var after = stack(fixture);
            assertEquals(after.subList(before.size(), after.size()), tail);
            for (int i = 0; i < inserted.size(); i++) { assertSame(after.get(i), inserted.get(inserted.size() - 1 - i)); }
        }
        @Override public List<SpellAbility> orderSimultaneousSa(List<SpellAbility> abilities) {
            inserted = List.copyOf(super.orderSimultaneousSa(abilities));
            assertEquals(new HashSet<>(inserted), new HashSet<>(before));
            return inserted;
        }
        @Override public void playSpellAbilityNoStack(SpellAbility ability, boolean setupTargets) {
            calls.add("resolve:" + getGame().getPlayers().indexOf(player));
            assertFalse(setupTargets);
            assertSame(ability, ((WrappedAbility) inserted.get(inserted.size() - 1 - resolved++)).getWrappedAbility());
            assertSame(ability, ((WrappedAbility) getGame().getStack().peekAbility()).getWrappedAbility());
            assertTrue(getGame().getStack().isResolving());
            super.playSpellAbilityNoStack(ability, setupTargets);
        }
        @Override public boolean confirmTrigger(WrappedAbility ability) { throw new AssertionError("Unexpected confirmation"); }
        @Override public boolean playTrigger(Card host, WrappedAbility ability, boolean mandatory) { throw new AssertionError("Unexpected static trigger"); }
    }

    private static List<List<Integer>> counts() { return List.of(List.of(1, 1), List.of(1, 2), List.of(2, 1), List.of(2, 2)); }

    @Test public void originalBoundaryKeepsApnapSeparateFromEachPlayersInternalOrder() {
        for (int active : List.of(0, 1)) {
            for (var counts : counts()) {
                var expected = run(fixture(new LobbyPlayerAi("first", null), counts, active));
                var f = fixture(new LobbyPlayerAi("first", null), counts, active);
                var calls = new ArrayList<String>();
                for (Player player : f.base().game().getPlayers()) { player.dangerouslySetController(new Probe(f, player, calls)); }
                assertEquals(run(f), expected);
                var expectedCalls = new ArrayList<>(List.of("prepare:" + active, "prepare:" + (1 - active)));
                for (int seat : List.of(1 - active, active)) { expectedCalls.addAll(Collections.nCopies(counts.get(seat), "resolve:" + seat)); }
                assertEquals(calls, expectedCalls);
            }
        }
    }

    @Test public void bothTeachersAreNeutralAndReplayUsesOnlyPerPlayerClientBindings() {
        for (int active : List.of(0, 1)) {
            for (var counts : counts()) {
                var baseline = run(fixture(new LobbyPlayerAi("first", null), counts, active));
                var recording = new HelpfulHunterTest.Recording();
                var teacher = fixture(new LobbyPlayerTeacher("first", recording), counts, active);
                HelpfulHunterTest.opponent(teacher.base(), recording);
                assertEquals(run(teacher), baseline);
                assertNull(recording.pending);
                assertNull(recording.execution);
                var kinds = recording.requests.stream().map(r -> ((Map<?, ?>) r.get("question")).get("kind")).toList();
                assertEquals(kinds.subList(0, 2), List.of("trigger_prepare", "trigger_prepare"));
                assertEquals(Collections.frequency(kinds, "trigger_prepare"), 2);
                int[] position = {0};
                var replay = fixture(new LobbyPlayerRl("first", request -> {
                    assertEquals(request, recording.requests.get(position[0]));
                    return recording.answers.get(position[0]++);
                }), counts, active);
                Player opponent = replay.base().game().getPlayers().get(1);
                opponent.dangerouslySetController(new PlayerControllerRl(replay.base().game(), opponent, new LobbyPlayerAi("second", null), request -> {
                    assertEquals(request, recording.requests.get(position[0]));
                    return recording.answers.get(position[0]++);
                }));
                var applied = run(replay);
                assertEquals(applied.facts(), baseline.facts());
                assertEquals(applied.intermediate(), baseline.intermediate());
                assertEquals(applied.events(), baseline.events());
                assertEquals(position[0], recording.requests.size());
                assertTrue(replay.base().game().getPlayers().stream().noneMatch(p -> p.getController().isAI()));
                HelpfulHunterTest.close(teacher.base()); HelpfulHunterTest.close(replay.base());
            }
        }
    }

    @Test public void preparationFailureStaysWithItsControllerAndPreservesPreviouslyInsertedBatch() {
        for (boolean teacher : List.of(false, true)) {
            for (int active : List.of(0, 1)) {
                for (int failing : List.of(0, 1)) {
                    var f = fixture(new LobbyPlayerAi("first", null), List.of(2, 2), active);
                    var game = f.base().game();
                    var failure = new IllegalStateException("APNAP preparation failed");
                    var recorded = new HelpfulHunterTest.Recording();
                    for (int seat = 0; seat < 2; seat++) {
                        Player player = game.getPlayers().get(seat);
                        boolean fails = seat == failing;
                        if (teacher) {
                            var callback = new IDecisionRecordCallback() {
                                @Override public void before(Map<String, Object> request) {
                                    if (fails) { throw failure; }
                                    recorded.before(request);
                                }
                                @Override public void after(List<Integer> answer) { recorded.after(answer); }
                            };
                            player.dangerouslySetController(new PlayerControllerTeacher(game, player, new LobbyPlayerAi(player.getName(), null), callback));
                        } else {
                            player.dangerouslySetController(new PlayerControllerRl(game, player, new LobbyPlayerAi(player.getName(), null), request -> {
                                if (fails) { throw failure; }
                                return ((Map<?, ?>) request.get("question")).get("kind").equals("trigger_prepare") ? List.of(0, 1) : List.of(0);
                            }));
                        }
                    }
                    enter(f);
                    assertSame(expectThrows(IllegalStateException.class, game.getStack()::addAllTriggeredAbilitiesToStack), failure);
                    var current = stack(f);
                    assertEquals(current.size(), failing == active ? 1 : 3);
                    assertSame(current.get(current.size() - 1), f.base().spell());
                    assertTrue(current.subList(0, current.size() - 1).stream().allMatch(sa -> sa.getActivatingPlayer() == game.getPlayers().get(active)));
                    assertSame(expectThrows(IllegalStateException.class, game.getPlayers().get(failing).getController()::chooseSpellAbilityToPlay), failure);
                    Player healthy = game.getPlayers().get(1 - failing);
                    game.getPhaseHandler().setPriority(healthy);
                    assertNull(healthy.getController().chooseSpellAbilityToPlay());
                    assertNull(recorded.pending);
                    for (Player player : game.getPlayers()) {
                        assertEquals(player.getLife(), 20);
                        assertEquals(player.getCardsIn(ZoneType.Library).size(), 8);
                        assertTrue(player.getCardsIn(ZoneType.Hand).isEmpty());
                    }
                    HelpfulHunterTest.close(f.base());
                }
            }
        }
    }

    @Test public void foreignResolutionCannotUseAnotherPlayersExactInnerAbility() {
        for (boolean teacher : List.of(false, true)) {
            var f = fixture(new LobbyPlayerAi("first", null), List.of(1, 1), 0);
            var game = f.base().game();
            enter(f);
            assertTrue(game.getStack().addAllTriggeredAbilitiesToStack());
            var top = (WrappedAbility) game.getStack().peekAbility();
            var players = game.getPlayers();
            for (Player player : players) {
                if (teacher) {
                    player.dangerouslySetController(new PlayerControllerTeacher(game, player,
                            new LobbyPlayerAi(player.getName(), null), new HelpfulHunterTest.Recording()));
                } else {
                    player.dangerouslySetController(new PlayerControllerRl(game, player,
                            new LobbyPlayerAi(player.getName(), null), request -> { throw new AssertionError("Unexpected automatic effect decision"); }));
                }
            }
            assertSame(top.getActivatingPlayer(), players.get(1));
            game.getStack().setResolving(true);
            assertThrows(IllegalArgumentException.class, () -> players.get(0).getController().playSpellAbilityNoStack(top.getWrappedAbility(), false));
            game.getStack().setResolving(false);
            assertTrue(players.get(1).getCardsIn(ZoneType.Hand).isEmpty());
            game.getStack().resolveStack();
            assertEquals(players.get(1).getCardsIn(ZoneType.Hand).stream().map(Card::getName).toList(), List.of("Swamp"));
            assertTrue(players.get(0).getCardsIn(ZoneType.Hand).isEmpty());
            HelpfulHunterTest.close(f.base());
        }
    }
}
