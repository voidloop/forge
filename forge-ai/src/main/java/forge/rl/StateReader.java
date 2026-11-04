package forge.rl;

import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardState;
import forge.game.card.CardView;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;

import java.security.SecureRandom;
import java.util.*;

/** Observer-bound access to Forge. Only detached data crosses this boundary. */
public final class StateReader implements AutoCloseable {
    private final Player observer;
    private final Map<Integer, Integer> aliases = new HashMap<>();
    private final Map<Integer, Integer> stackAliases = new HashMap<>();
    private final Map<Trigger, Integer> triggerAliases = new IdentityHashMap<>();
    private final Set<Integer> usedAliases = new HashSet<>();
    private final Random random;
    private KnowledgeEventObserver events;

    public StateReader(Player observer) {
        this(observer, new SecureRandom());
    }

    /** Reproducible aliases for private recording/replay setup, independent of game randomness. */
    public StateReader(Player observer, long aliasSeed) {
        this(observer, new Random(aliasSeed));
    }

    StateReader(Player observer, Random random) {
        this.observer = Objects.requireNonNull(observer);
        this.random = random;
    }

    public void observe(IKnowledgeEventCallback callback) {
        if (events != null) { throw new IllegalStateException("Observer is already bound"); }
        events = new KnowledgeEventObserver(observer, this, callback);
        observer.getGame().subscribeToEvents(events);
    }

    void reveal(List<CardView> cards, ZoneType zone) {
        if (events == null) { throw new IllegalStateException("Observer is not bound"); }
        events.reveal(cards, zone);
    }

    void libraryTopVisible(Player owner, CardCollectionView cards) {
        if (events == null) { throw new IllegalStateException("Observer is not bound"); }
        events.libraryTopVisible(owner, cards);
    }

    void scryChosen(Player owner, CardCollectionView kept, CardCollectionView bottom) {
        if (events == null) { throw new IllegalStateException("Observer is not bound"); }
        events.scryChosen(owner, kept, bottom);
    }

    void surveilChosen(Player owner, CardCollectionView kept, CardCollectionView moved) {
        if (events == null) { throw new IllegalStateException("Observer is not bound"); }
        events.surveilChosen(owner, kept, moved);
    }

    void digBottomChosen(Player chooser, SpellAbility source, CardCollectionView ordered) {
        if (source == null || source.getApi() != ApiType.Dig || !observer.getGame().getStack().isResolving()
                || !"Hand".equals(source.getParamOrDefault("DestinationZone", "Hand"))
                || !"Library".equals(source.getParamOrDefault("DestinationZone2", "Library"))
                || !"-1".equals(source.getParamOrDefault("LibraryPosition2", "-1"))
                || ordered.stream().anyMatch(card -> card.getZone() != chooser.getZone(ZoneType.Library))) { return; }
        if (events == null) { throw new IllegalStateException("Observer is not bound"); }
        if (chooser != observer && chooser.getControllingPlayer() != observer) {
            throw new IllegalStateException("Library order routed to an unauthorized observer");
        }
        events.libraryBottomChosen(chooser, ordered);
    }

    void tempShowCard(Player chooser, Card card, Map<Player, CardCollection> temporaryViews) {
        for (Player viewer : chooser != observer && chooser.getControllingPlayer() == observer
                ? List.of(chooser, observer) : List.of(chooser)) {
            if (!card.mayPlayerLook(viewer)) {
                card.addMayLookTemp(viewer);
                temporaryViews.computeIfAbsent(viewer, ignored -> new CardCollection()).add(card);
            }
        }
    }

    static void endTempShowCards(Map<Player, CardCollection> temporaryViews) {
        temporaryViews.forEach((viewer, cards) -> cards.forEach(card -> card.removeMayLookTemp(viewer)));
        temporaryViews.clear();
    }

    public Map<String, Object> snapshot() {
        if (events != null) { events.flush(); }
        return ObservationReader.read(observer, this);
    }

    public Map<String, Object> printedCards(String[] names) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : names) { result.put(name, CardReader.printed(name)); }
        return result;
    }

    /** Called only after the game worker stops; release the Python callback. */
    public void close() {
        if (events != null) { events.close(); events = null; }
    }

    boolean canSee(CardView card) {
        return card != null && card.canBeShownTo(observer.getView());
    }

    boolean canIdentify(CardView card) {
        return canIdentify(card, observer);
    }

    static boolean canIdentify(CardView card, Player viewer) {
        return card != null && viewer != null && card.canBeShownTo(viewer.getView())
                && card.canFaceDownBeShownTo(viewer.getView());
    }

    CardState identity(Card card) {
        return identity(card, observer);
    }

    static CardState identity(Card card, Player viewer) {
        if (card == null || !canIdentify(card.getView(), viewer)) { return null; }
        return card.isFaceDown() ? card.getAlternateState() : card.getCurrentState();
    }

    String visibleName(CardView card) {
        if (!canIdentify(card)) { return null; }
        return disclosedName(card);
    }

    // An explicit native reveal authorizes face-up identities in hidden zones.
    String disclosedName(CardView card) {
        if (card == null) { return null; }
        if (!card.isFaceDown()) { return card.getOracleName(); }
        return card.canFaceDownBeShownTo(observer.getView()) && card.getAlternateState() != null
                ? card.getAlternateState().getOracleName() : null;
    }

    private int nextAlias() {
        int value;
        do { value = random.nextInt(Integer.MAX_VALUE - 1) + 1; }
        while (!usedAliases.add(value));
        return value;
    }

    int alias(int nativeId) { return aliases.computeIfAbsent(nativeId, ignored -> nextAlias()); }
    int stackAlias(int nativeId) { return stackAliases.computeIfAbsent(nativeId, ignored -> nextAlias()); }
    int triggerAlias(Trigger trigger) { return triggerAliases.computeIfAbsent(trigger, ignored -> nextAlias()); }

    void conceal(int nativeId) { aliases.remove(nativeId); }

    Map<String, Object> rememberedReference(int nativeId) {
        Integer id = aliases.get(nativeId);
        return id == null ? null : data("kind", "card", "id", id);
    }

    Integer referenceId(CardView card) { return canSee(card) ? alias(card.getId()) : null; }

    String side(Player player) {
        return player == null ? "none" : player == observer ? "self" : "opponent";
    }

    public Map<String, Object> reference(Object entity) {
        if (entity instanceof SpellAbility ability) {
            var entry = observer.getGame().getStack().getInstanceMatchingSpellAbilityID(ability);
            return entry != null && entry.getSpellAbility() == ability
                    ? data("kind", "stack_object", "id", stackAlias(entry.getId())) : null;
        }
        if (entity instanceof Player player) { return reference(player.getView()); }
        if (entity instanceof PlayerView player) { return data("kind", "player", "id", player.getId()); }
        CardView card = entity instanceof Card value ? value.getView()
                : entity instanceof CardView value ? value : null;
        Integer id = referenceId(card);
        return id == null ? null : data("kind", "card", "id", id);
    }

    public static Map<String, Object> data(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) { result.put((String) pairs[i], pairs[i + 1]); }
        return result;
    }
}
