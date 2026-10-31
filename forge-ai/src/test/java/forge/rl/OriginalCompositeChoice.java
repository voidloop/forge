package forge.rl;

import forge.CardStorageReader;
import forge.ImageKeys;
import forge.StaticData;
import forge.ai.AiController;
import forge.ai.ComputerUtil;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.card.CardType;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.ability.effects.CharmEffect;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.Spell;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.FileSection;
import forge.util.FileUtil;
import forge.util.Lang;
import forge.util.Localizer;
import forge.util.MyRandom;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/** Standalone proof against original Forge; no production adapter or model protocol. */
public final class OriginalCompositeChoice {
    private record Choice(int modeIndex, int targetIndex) {
        static Choice capture(SpellAbility spell, List<SpellAbility> bindings) {
            require(spell.getChosenList() != null && spell.getChosenList().size() == 1,
                    "Expected one selected mode");
            AbilitySub chain = spell.getSubAbility();
            require(chain != null && chain.getSubAbility() == null && chain.getTargets().size() == 1,
                    "Expected one prepared subability and one target");
            int mode = spell.getAdditionalAbilityList("Choices").indexOf(spell.getChosenList().get(0));
            int target = bindings.indexOf(chain.getTargets().getFirstTargetedSpell());
            require(mode >= 0 && target >= 0, "Unbound teacher response");
            return new Choice(mode, target);
        }

        void apply(SpellAbility spell, List<SpellAbility> bindings) {
            // shortcut: one Counter mode and spell target; other responses need their own proof.
            List<AbilitySub> modes = spell.getAdditionalAbilityList("Choices");
            if (modeIndex < 0 || modeIndex >= modes.size() || targetIndex < 0 || targetIndex >= bindings.size()
                    || spell.getChosenList() != null || spell.getSubAbility() != null) {
                throw new IllegalArgumentException("Invalid response or non-fresh ability");
            }
            AbilitySub mode = modes.get(modeIndex);
            SpellAbility target = bindings.get(targetIndex);
            if (mode.getApi() != ApiType.Counter || mode.getSubAbility() != null
                    || !mode.getTargets().isEmpty() || !mode.canTarget(target)
                    || spell.getActivatingPlayer().getGame().getStack().getInstanceMatchingSpellAbilityID(target) == null) {
                throw new IllegalArgumentException("Invalid mode or live stack target");
            }
            mode.getTargets().add(target);
            spell.setChosenList(new ArrayList<>(List.of(mode)));
            CharmEffect.chainAbilities(spell, spell.getChosenList());
        }
    }

    private static final class RecordingController extends PlayerControllerAi {
        private Choice answer;
        private List<Object> before;
        private List<Object> after;
        private int calls;

        RecordingController(Game game, Player player, LobbyPlayerAi lobby) {
            super(game, player, lobby);
        }

        @Override public boolean playSaFromPlayEffect(SpellAbility ability) {
            require(++calls == 1, "Unexpected additional teacher request");
            before = observe(getGame());
            List<SpellAbility> bindings = stackBindings(getGame());
            boolean executed = super.playSaFromPlayEffect(ability);
            require(executed && getGame().getStack().peekAbility() == ability,
                    "Teacher execution did not deliver the offered spell");
            answer = Choice.capture(ability, bindings);
            after = observe(getGame());
            return executed;
        }
    }

    private static final class ReplayingController extends PlayerControllerAi {
        private final Choice answer;
        private int calls;

        ReplayingController(Game game, Player player, LobbyPlayerAi lobby, Choice answer) {
            super(game, player, lobby);
            this.answer = answer;
        }

        @Override public List<SpellAbility> chooseSpellAbilityToPlay() {
            throw new AssertionError("Replay must not request a new action");
        }

        @Override public List<AbilitySub> chooseModeForAbility(SpellAbility ability, List<AbilitySub> possible,
                int min, int max, boolean repeat) {
            throw new AssertionError("Replay must not request a new mode");
        }

        @Override public boolean chooseTargetsFor(SpellAbility ability) {
            throw new AssertionError("Replay must not request new targets");
        }

        @Override public boolean playSaFromPlayEffect(SpellAbility ability) {
            require(++calls == 1, "Unexpected additional replay request");
            answer.apply(ability, stackBindings(getGame()));
            return ComputerUtil.playStack(ability, getPlayer(), getGame());
        }
    }

    private record Fixture(Game game, Player player, Spell offered, SpellAbility target) { }
    private record Outcome(List<Object> before, List<Object> stack, List<Object> resolved, long rngAfter) { }

    private static Fixture fixture(String role, Choice answer) {
        MyRandom.setRandom(new Random(0));
        LobbyPlayerAi actor = role.equals("baseline") ? new LobbyPlayerAi("first", null)
                : new LobbyPlayerAi("first", null) {
                    @Override public Player createIngamePlayer(Game game, int id) {
                        Player player = new Player(getName(), game, id);
                        PlayerControllerAi controller = role.equals("teacher")
                                ? new RecordingController(game, player, this)
                                : new ReplayingController(game, player, this, answer);
                        controller.setUseSimulation(false);
                        player.setFirstController(controller);
                        return player;
                    }
                };
        List<RegisteredPlayer> seats = List.of(
                new RegisteredPlayer(new Deck("first")).setPlayer(actor),
                new RegisteredPlayer(new Deck("second")).setPlayer(new LobbyPlayerAi("second", null)));
        Game game = new Match(new GameRules(GameType.Constructed), seats, "original composite").createGame();
        game.getRegisteredPlayers().forEach(player -> player.setLife(20, null));
        game.setAge(GameStage.Play);
        Player player = game.getRegisteredPlayers().get(0);
        Player opponent = game.getRegisteredPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, player, false, 6);
        Card creature = add(game, opponent, "Grizzly Bears");
        SpellAbility target = AbilityUtils.getBasicSpellsFromPlayEffect(creature, opponent).get(0);
        target.setHostCard(game.getAction().moveToStack(creature, target));
        game.getStack().add(target);
        Card source = add(game, player, "Insidious Will");
        Spell offered = (Spell) AbilityUtils.getBasicSpellsFromPlayEffect(source, player).get(0).copyWithNoManaCost();
        return new Fixture(game, player, offered, target);
    }

    private static Card add(Game game, Player player, String name) {
        Card card = CardFactory.getCard(StaticData.instance().getCommonCards().getCard(name), player, game);
        player.getZone(ZoneType.Hand).add(card);
        return card;
    }

    private static List<SpellAbility> stackBindings(Game game) {
        List<SpellAbility> result = new ArrayList<>();
        game.getStack().forEach(item -> result.add(item.getSpellAbility()));
        return List.copyOf(result);
    }

    /** Fixture-only public facts, with stack identities represented by their current positions. */
    private static List<Object> observe(Game game) {
        List<Object> state = new ArrayList<>();
        List<Player> seats = game.getRegisteredPlayers();
        state.add(List.of(game.getPhaseHandler().getPhase(), game.getPhaseHandler().getTurn(),
                seats.indexOf(game.getPhaseHandler().getPlayerTurn()), game.isGameOver()));
        for (Player player : seats) {
            List<Object> zones = new ArrayList<>();
            for (ZoneType zone : List.of(ZoneType.Hand, ZoneType.Library, ZoneType.Battlefield,
                    ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command)) {
                zones.add(List.of(zone, player.getCardsIn(zone).stream().map(card -> List.of(
                        card.getName(), card.getCurrentStateName(), seats.indexOf(card.getController()),
                        card.isTapped(), card.getDamage())).toList()));
            }
            List<Byte> mana = new ArrayList<>();
            player.getManaPool().forEach(unit -> mana.add(unit.getColor()));
            state.add(List.of(player.getLife(), zones, List.copyOf(mana), player.getSpellsCastThisTurn(),
                    player.getSpellsCastThisGame(), player.getExpentThisTurn()));
        }
        List<SpellAbility> bindings = stackBindings(game);
        for (SpellAbility ability : bindings) {
            List<Object> parts = new ArrayList<>();
            for (SpellAbility part = ability; part != null; part = part.getSubAbility()) {
                List<Integer> targets = new ArrayList<>();
                for (SpellAbility target : part.getTargets().getTargetSpells()) {
                    int slot = bindings.indexOf(target);
                    require(slot >= 0, "Unbound observed stack target");
                    targets.add(slot);
                }
                require(part.getTargets().size() == targets.size(), "Unexpected non-spell target in fixture");
                parts.add(List.of(part.getApi(), List.copyOf(targets), Objects.toString(part.getXManaCostPaid(), "unset")));
            }
            List<Integer> modes = ability.getChosenList() == null ? List.of()
                    : ability.getChosenList().stream().map(ability.getAdditionalAbilityList("Choices")::indexOf).toList();
            state.add(List.of(ability.getHostCard().getName(), seats.indexOf(ability.getActivatingPlayer()), modes, parts));
        }
        return List.copyOf(state);
    }

    private static Outcome execute(Fixture fixture) {
        List<Object> before = observe(fixture.game());
        require(fixture.player().getController().playSaFromPlayEffect(fixture.offered()), "Cast failed");
        require(fixture.offered().getChosenList().size() == 1
                && fixture.offered().getChosenList().get(0).getApi() == ApiType.Counter, "Wrong selected mode");
        require(fixture.offered().getSubAbility().getTargets().getFirstTargetedSpell() == fixture.target(),
                "Prepared chain targets the wrong game object");
        List<Object> stack = observe(fixture.game());
        fixture.game().getStack().resolveStack();
        require(fixture.game().getStack().isEmpty(), "Counter did not empty the stack");
        for (Card card : List.of(fixture.offered().getHostCard(), fixture.target().getHostCard())) {
            require(fixture.game().getCardState(card, null).isInZone(ZoneType.Graveyard), "Wrong resolution zone");
        }
        return new Outcome(before, stack, observe(fixture.game()), MyRandom.getRandom().nextLong());
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }

    public static void main(String[] args) throws Exception {
        Path forge = Path.of(args[0]).toRealPath();
        for (var entry : Map.of(AiController.class, "forge-ai", PlayerControllerAi.class, "forge-ai",
                Game.class, "forge-game", MyRandom.class, "forge-core").entrySet()) {
            Path loaded = Path.of(entry.getKey().getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            require(loaded.equals(forge.resolve(entry.getValue() + "/target/classes").toRealPath()),
                    "Engine class loaded outside original build: " + loaded);
            System.out.println("ORIGIN " + entry.getKey().getName() + " " + loaded);
        }
        String res = forge.resolve("forge-gui/res").toString() + "/";
        Lang.createInstance("en-US");
        Localizer.getInstance().initialize("en-US", res + "languages");
        ImageKeys.initializeDirs("", Map.of(), "", "", "", "", "", "", "");
        FileSection.parseSections(FileUtil.readFile(res + "lists/TypeLists.txt")).forEach(CardType.Helper::parseTypes);
        CardType.Constant.LOADED.set();
        new StaticData(new CardStorageReader(res + "cardsfolder/", null, false),
                new CardStorageReader(res + "tokenscripts/", null, false), null, null,
                res + "editions", forge.resolve("forge-ai/target/custom-editions").toString(),
                res + "blockdata", res + "setlookup", "Latest Art All Editions", true, false, false, false);

        Outcome baseline = execute(fixture("baseline", null));
        Fixture teacher = fixture("teacher", null);
        Outcome recorded = execute(teacher);
        RecordingController recorder = (RecordingController) teacher.player().getController();
        require(recorder.calls == 1 && recorder.answer.equals(new Choice(0, 0)), "Wrong recorded response");
        require(recorded.before().equals(recorder.before) && recorded.stack().equals(recorder.after),
                "Recorder did not retain the public method boundaries");
        require(baseline.equals(recorded), "Recording changed fixture state or RNG");

        Choice detached = new Choice(recorder.answer.modeIndex(), recorder.answer.targetIndex());
        Fixture replay = fixture("replay", detached);
        require(replay.offered() != teacher.offered() && replay.target() != teacher.target(), "Reused teacher objects");
        for (Choice invalid : List.of(new Choice(99, 0), new Choice(0, 99))) {
            try {
                invalid.apply(replay.offered(), stackBindings(replay.game()));
                throw new AssertionError("Invalid response accepted");
            } catch (IllegalArgumentException expected) {
                require(replay.offered().getChosenList() == null && replay.offered().getSubAbility() == null
                        && replay.offered().getAdditionalAbilityList("Choices").stream().allMatch(mode -> mode.getTargets().isEmpty()),
                        "Invalid response partially changed the ability");
            }
        }
        Outcome applied = execute(replay);
        require(recorded.before().equals(applied.before()) && recorded.stack().equals(applied.stack())
                && recorded.resolved().equals(applied.resolved()), "Replay changed configuration or outcome");
        require(((ReplayingController) replay.player().getController()).calls == 1, "Replay request count mismatch");
        System.out.println("PASS: original baseline and recorder have equal state/RNG; " + detached
                + " reapplied in a new Game without strategic AI; before/stack/resolved fixture facts match; invalid indices rejected");
    }
}
