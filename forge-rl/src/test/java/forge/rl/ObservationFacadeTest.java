package forge.rl;

import forge.CardStorageReader;
import forge.ImageKeys;
import forge.StaticData;
import forge.card.CardType;
import forge.card.CardStateName;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.ability.AbilityUtils;
import forge.game.GameActionUtil;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.card.CounterType;
import forge.game.cost.CostDiscard;
import forge.game.cost.CostPayLife;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.trigger.WrappedAbility;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventShuffle;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.util.FileSection;
import forge.util.FileUtil;
import forge.util.Lang;
import forge.util.Localizer;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.*;

import static org.testng.Assert.*;

public class ObservationFacadeTest {
    @BeforeClass
    public void initializeForge() {
        String res = "../forge-gui/res/";
        Lang.createInstance("en-US");
        Localizer.getInstance().initialize("en-US", res + "languages");
        ImageKeys.initializeDirs("", Map.of(), "", "", "", "", "", "", "");
        FileSection.parseSections(FileUtil.readFile(res + "lists/TypeLists.txt"))
                .forEach(CardType.Helper::parseTypes);
        CardType.Constant.LOADED.set();
        new StaticData(new CardStorageReader(res + "cardsfolder/", null, false),
                new CardStorageReader(res + "tokenscripts/", null, false), null, null,
                res + "editions", "target/custom-editions", res + "blockdata", res + "setlookup",
                "Latest Art All Editions", true, false, false, false);
    }

    private record Position(Game game, Player observer, Player opponent, ObservationFacade view) { }

    private Position position() {
        var registrations = List.of(
                new RegisteredPlayer(new Deck("observer")).setPlayer(new LobbyPlayerRl("observer", request -> List.of())),
                new RegisteredPlayer(new Deck("opponent")).setPlayer(new LobbyPlayerRl("opponent", request -> List.of())));
        Game game = new Match(new GameRules(GameType.Constructed), registrations, "facade test").createGame();
        Player observer = game.getRegisteredPlayers().get(0);
        Player opponent = game.getRegisteredPlayers().get(1);
        observer.setLife(20, null);
        opponent.setLife(20, null);
        return new Position(game, observer, opponent, new ObservationFacade(observer, new Random(0)));
    }

    private Card add(Position position, String name, Player owner, ZoneType zone) {
        Card card = CardFactory.getCard(StaticData.instance().getCommonCards().getCard(name), owner, position.game());
        (zone == ZoneType.Stack ? position.game().getStackZone() : owner.getZone(zone)).add(card);
        return card;
    }

    @DataProvider
    public Object[][] hiddenZones() {
        return new Object[][] {{ZoneType.Hand}, {ZoneType.Library}, {ZoneType.Exile}, {ZoneType.Battlefield}, {ZoneType.Stack}};
    }

    @Test(dataProvider = "hiddenZones")
    public void hiddenNamesCannotChangeAnyExportedData(ZoneType zone) {
        Position position = position();
        Card card = add(position, "Serra Angel", position.opponent(), zone);
        if (zone != ZoneType.Hand && zone != ZoneType.Library) { assertTrue(card.turnFaceDown(true)); }
        var ability = card.getSpellAbilities().isEmpty() ? null : card.getSpellAbilities().get(0);
        var context = new LinkedHashMap<String, Object>();
        context.put("nested", Map.of("cards", List.of(card, card.getState(CardStateName.Original))));
        context.put("opaque", new StringBuilder("Serra Angel"));
        var options = new ArrayList<Object>(List.of(card, card.getView(), card.getState(CardStateName.Original)));
        if (ability != null) { options.add(ability); }
        NativeDecision request = new NativeDecision("choice", position.observer(), null, "Choose", options,
                1, 1, false, false, false, false, null, context);
        var before = position.view().snapshot();
        var knowledge = (Map<?, ?>) before.get("knowledge");
        for (Object hidden : (List<?>) knowledge.get("zones")) {
            assertFalse(((Map<?, ?>) hidden).containsKey("ids"));
            assertEquals(((Map<?, ?>) hidden).get("token_ids"), List.of());
        }
        var decision = position.view().decision(request);
        card.getState(CardStateName.Original).setName("Black Lotus");
        assertEquals(position.view().snapshot(), before);
        assertEquals(position.view().decision(request), decision);
        assertFalse(decision.toString().contains("Serra Angel"));
        assertFalse(decision.toString().contains("Black Lotus"));
        assertDetached(before);
        assertDetached(decision);
        card.addMayLookTemp(position.observer());
        assertNotNull(position.view().visibleName(card.getView()));
        assertTrue(position.view().decision(request).toString().contains("Black Lotus"));
    }

    @Test
    public void aliasesAreRetiredAndNeverRecycleAFormerIdentity() {
        Position position = position();
        Card card = add(position, "Serra Angel", position.opponent(), ZoneType.Library);
        int original = position.view().alias(card.getId());
        position.view().conceal(card.getId());
        assertNotEquals(position.view().alias(card.getId()), original);
        int next = position.view().alias(card.getId());
        var callback = new Events();
        var events = new KnowledgeEventObserver(position.observer(), position.view(), callback);
        events.onShuffle(new GameEventShuffle(position.opponent()));
        assertTrue(callback.shuffled);
        assertNotEquals(position.view().alias(card.getId()), next);
        assertNotEquals(position.view().alias(card.getId()), original);
    }

    @Test
    public void hiddenExitWaitsForTheFinalNativePermissions() {
        Position position = position();
        Card card = add(position, "Serra Angel", position.opponent(), ZoneType.Library);
        var callback = new Events();
        var events = new KnowledgeEventObserver(position.observer(), position.view(), callback);
        events.onCardChangeZone(new GameEventCardChangeZone(card, position.opponent().getZone(ZoneType.Library), position.opponent().getZone(ZoneType.Exile)));
        position.opponent().getZone(ZoneType.Library).remove(card);
        position.opponent().getZone(ZoneType.Exile).add(card);
        assertTrue(card.turnFaceDown(true));
        assertTrue(callback.records.isEmpty());
        events.flush();
        assertEquals(callback.records, List.of("anonymous:Library:Exile"));
        assertNull(position.view().visibleName(card.getView()));
        card.addMayLookTemp(position.observer());
        assertEquals(position.view().visibleName(card.getView()), "Serra Angel");
    }

    @Test
    public void cardFactsKeepFaceDownCharacteristicsAndPublishedChoices() {
        Position position = position();
        Card card = add(position, "Serra Angel", position.opponent(), ZoneType.Battlefield);
        assertEquals(CardReader.read(card, position.view(), new Combat(position.observer())).get("dealt_first_strike_damage"), false);
        card.setSecretChosenType("Elf");
        var before = CardReader.read(card, position.view(), null);
        card.setSecretChosenType("Goblin");
        assertEquals(CardReader.read(card, position.view(), null), before);
        card.revealChosenType();
        assertEquals(CardReader.read(card, position.view(), null).get("chosen_type"), "Goblin");
        assertTrue(card.turnFaceDown(true));
        var facts = CardReader.read(card, position.view(), null);
        assertNull(facts.get("card_name"));
        assertNull(facts.get("oracle_text"));
        assertEquals(facts.get("power"), 2);
        assertEquals(facts.get("card_types"), List.of("creature"));
        position.opponent().getZone(ZoneType.Battlefield).remove(card);
        position.opponent().getZone(ZoneType.Exile).add(card);
        facts = CardReader.read(card, position.view(), null);
        assertNull(facts.get("power"));
        assertNull(facts.get("copiable"));
        assertEquals(facts.get("card_types"), List.of());
    }

    @Test
    public void actionTextUsesADetachedCopyAndNativeValidationStillRuns() {
        Position position = position();
        Card card = add(position, "Bitter Triumph", position.observer(), ZoneType.Stack);
        var root = card.getSpellAbilities().get(0);
        var sub = root.getSubAbility();
        String description = root.getDescription();
        String text = PrintedText.action(root);
        assertNotNull(text);
        assertEquals(root.getDescription(), description);
        assertSame(root.getSubAbility(), sub);
        NativeDecision request = new NativeDecision("choice", position.observer(), root, "Choose", List.of(card),
                1, 1, false, false, false, false, null);
        assertThrows(IllegalArgumentException.class, () -> request.validateAnswer(List.of(1)));
        request.validateAnswer(List.of(0));
    }

    @DataProvider
    public Object[][] copyCases() {
        return new Object[][] {{"counters"}, {"growth"}, {"flying"}, {"frog"},
                {"copy_of_copy"}, {"modified_copy"}, {"hidden_self"}, {"hidden_opponent"}};
    }

    @Test(dataProvider = "copyCases")
    public void copyLayerMatchesNativeCopyResults(String example) {
        Position position = position();
        position.game().getPhaseHandler().devModeSet(PhaseType.MAIN2, position.observer(), false, 20);
        Card target = add(position, Set.of("counters", "growth", "flying").contains(example) ? "Grizzly Bears" : "Serra Angel",
                example.equals("hidden_opponent") ? position.opponent() : position.observer(), ZoneType.Battlefield);
        if (example.equals("counters")) { target.setCounters(CounterType.getType("P1P1"), 6); }
        Map<String, String> changes = Map.of("growth", "Giant Growth", "flying", "Jump", "frog", "Turn to Frog");
        if (changes.containsKey(example)) { resolve(position, changes.get(example), target, null); }
        if (example.equals("copy_of_copy") || example.equals("modified_copy")) {
            resolve(position, example.equals("copy_of_copy") ? "Doppelgang" : "Croaking Counterpart", target,
                    example.equals("copy_of_copy") ? 1 : null);
            target = position.observer().getCardsIn(ZoneType.Battlefield).stream().filter(Card::isToken).findFirst().orElseThrow();
        }
        if (example.startsWith("hidden")) { assertTrue(target.turnFaceDown(true)); }
        Set<Integer> previous = new HashSet<>();
        position.observer().getCardsIn(ZoneType.Battlefield).forEach(card -> previous.add(card.getId()));
        resolve(position, "Doppelgang", target, 1);
        Card copy = position.observer().getCardsIn(ZoneType.Battlefield).stream()
                .filter(card -> !previous.contains(card.getId())).findFirst().orElseThrow();
        var targetFacts = CardReader.read(target, position.view(), null);
        var copyFacts = CardReader.read(copy, position.view(), null);
        var copiable = (Map<?, ?>) targetFacts.get("copiable");
        assertEquals(copiable, copyFacts.get("copiable"));
        assertEquals(copiable.get("power"), copy.getNetPower());
        assertEquals(copiable.get("toughness"), copy.getNetToughness());
        if (Set.of("counters", "growth", "frog").contains(example)) { assertNotEquals(targetFacts.get("power"), copiable.get("power")); }
        if (example.equals("flying")) {
            assertTrue(((List<?>) targetFacts.get("keywords")).contains(List.of("flying", "")));
            assertFalse(((List<?>) copiable.get("keywords")).contains(List.of("flying", "")));
        }
        if (example.equals("frog")) { assertEquals(targetFacts.get("colors"), List.of("blue")); assertEquals(copiable.get("colors"), List.of("white")); }
        if (example.equals("modified_copy")) { assertEquals(targetFacts.get("base_power"), 4); assertEquals(copiable.get("power"), 1); }
        if (example.startsWith("hidden")) { assertEquals(copiable.get("power"), 2); assertEquals(copiable.get("colors"), List.of("colorless")); }
        if (example.equals("hidden_opponent")) { assertNull(targetFacts.get("card_name")); assertNull(targetFacts.get("oracle_text")); }
    }

    private void resolve(Position position, String name, Card target, Integer x) {
        Card source = add(position, name, position.observer(), ZoneType.Stack);
        var ability = source.getSpellAbilities().get(0);
        ability.setActivatingPlayer(position.observer());
        if (x != null) { ability.setXManaCostPaid(x); }
        ability.getTargets().add(target);
        AbilityUtils.resolve(ability);
    }

    @Test
    public void additionalCostTextAndWrappedTriggersKeepTheirLiveChains() {
        for (String spell : List.of("Bitter Triumph", "Dusk Mangler")) {
            for (boolean life : List.of(true, false)) {
                Position position = position();
                position.game().getPhaseHandler().devModeSet(PhaseType.MAIN2, position.observer(), false, 20);
                Card source = add(position, spell, position.observer(), ZoneType.Hand);
                add(position, "Grizzly Bears", position.observer(), ZoneType.Hand);
                add(position, "Centaur Courser", position.opponent(), ZoneType.Battlefield);
                for (int i = 0; i < 7; i++) { add(position, "Swamp", position.observer(), ZoneType.Battlefield); }
                var root = source.getSpellAbilities().get(0);
                root.setActivatingPlayer(position.observer());
                String expected = PrintedText.action(root);
                var variant = GameActionUtil.getAdditionalCostSpell(root).stream()
                        .filter(action -> action.getPayCosts().getCostParts().stream()
                                .anyMatch(cost -> life ? cost instanceof CostPayLife : cost instanceof CostDiscard))
                        .findFirst().orElseThrow();
                String description = variant.getDescription();
                var sub = variant.getSubAbility();
                assertEquals(PrintedText.action(variant), expected);
                assertEquals(variant.getDescription(), description);
                assertSame(variant.getSubAbility(), sub);
            }
        }
        Position position = position();
        Card card = add(position, "Phyrexian Rager", position.observer(), ZoneType.Battlefield);
        var trigger = card.getTriggers().iterator().next();
        var inner = trigger.ensureAbility();
        var wrapped = new WrappedAbility(trigger, inner, position.observer());
        wrapped.setActivatingPlayer(position.observer());
        var sub = wrapped.getSubAbility();
        assertNotNull(sub);
        assertEquals(PrintedText.action(wrapped), PrintedText.trigger(trigger));
        assertSame(wrapped.getSubAbility(), sub);
        assertSame(inner.getSubAbility(), sub);
    }

    private void assertDetached(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) { return; }
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> { assertTrue(key instanceof String); assertDetached(item); });
        } else if (value instanceof Collection<?> collection) {
            collection.forEach(this::assertDetached);
        } else { fail("Native object escaped: " + value.getClass()); }
    }

    private static final class Events implements IKnowledgeEventCallback {
        final List<String> records = new ArrayList<>();
        boolean shuffled;
        public void cardZoneChanged(int id, String name, boolean own, boolean token, String from, String to) { records.add(name); }
        public void anonymousZoneChange(boolean own, String from, String to) { records.add("anonymous:" + from + ":" + to); }
        public void playerShuffled(boolean own) { shuffled = true; }
        public void cardsRevealed(boolean own, String zone, List<Integer> ids, List<String> names, List<Boolean> tokens) { }
        public void libraryTopVisible(boolean own, List<Integer> ids, List<String> names, List<Boolean> tokens) { }
        public void publicEvent(String kind, boolean own, Integer id, String from, String to, Integer amount) { }
        public void publicEffectCreated(Map<String, Object> effect) { }
        public void publicEffectRemoved(int id) { }
    }
}
