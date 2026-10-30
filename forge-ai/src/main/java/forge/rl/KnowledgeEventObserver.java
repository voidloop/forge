package forge.rl;

import com.google.common.eventbus.Subscribe;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardView;
import forge.game.combat.CombatUtil;
import forge.game.event.GameEventAttackersDeclared;
import forge.game.event.GameEventBlockersDeclared;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventCardModeChosen;
import forge.game.event.GameEventLandPlayed;
import forge.game.event.GameEventMulligan;
import forge.game.event.GameEventShuffle;
import forge.game.event.GameEventScry;
import forge.game.event.GameEventSurveil;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventZone;
import forge.game.event.EventValueChangeType;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.game.zone.ZoneView;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Collection;
import java.util.Objects;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Collections;

/** Sends only facts visible to the RL player across the Java/Python boundary. */
public final class KnowledgeEventObserver {
    private final Player observer;
    private final ObservationFacade view;
    private IKnowledgeEventCallback callback;
    private record PendingMove(GameEventCardChangeZone event, String sourceName) { }
    private final List<PendingMove> pendingMoves = new ArrayList<>();
    private SpellAbility publicRevealSource;
    private final Map<Integer, Runnable> publicHandMoves = new HashMap<>();
    private final Map<Integer, PendingLibraryOrder> libraryOrders = new HashMap<>();
    private record LibraryOrderMove(int id, ZoneType destination) { }

    private static final class PendingLibraryOrder {
        final int topCount;
        final int movedCount;
        final ZoneType movedTo;
        final ArrayDeque<LibraryOrderMove> moves;
        final Runnable publish;
        boolean started;

        PendingLibraryOrder(int topCount, int movedCount, ZoneType movedTo, List<LibraryOrderMove> moves, Runnable publish) {
            this.topCount = topCount;
            this.movedCount = movedCount;
            this.movedTo = movedTo;
            this.moves = new ArrayDeque<>(moves);
            this.publish = publish;
        }
    }

    KnowledgeEventObserver(Player observer, ObservationFacade view, IKnowledgeEventCallback callback) {
        this.observer = observer;
        this.view = view;
        this.callback = callback;
    }

    @Subscribe
    public void onCardChangeZone(GameEventCardChangeZone event) {
        flush();
        final CardView card = event.card();
        if (card == null || card.getOwner() == null) { return; }
        final ZoneType from = zoneType(event.from());
        Runnable disclosed = from == ZoneType.Library ? publicHandMoves.remove(card.getId()) : null;
        if (disclosed != null && zoneType(event.to()) == ZoneType.Hand
                && card.getOwner().getId() != observer.getId()) {
            callback.libraryTopVisible(false, List.of(), List.of(), List.of());
            disclosed.run();
            return;
        }
        if (consumeLibraryOrderMove(card, from, zoneType(event.to()))) { return; }
        final String sourceName = card.getZone() == from && canIdentify(card)
                ? visibleName(card) : null;
        final PendingMove move = new PendingMove(event, sourceName);
        if (isPublic(zoneType(event.to())) && from != null && from.isHidden()) {
            // Forge applies face-down state and look grants after this event.
            pendingMoves.add(move);
            return;
        }
        emitCardChangeZone(move);
    }

    /** Deliver moves after Forge has applied their final visibility. */
    public void flush() {
        if (publicRevealSource != resolvingAbility()) {
            publicRevealSource = null;
            publicHandMoves.clear();
        }
        final List<PendingMove> pending = new ArrayList<>(pendingMoves);
        pendingMoves.clear();
        pending.forEach(this::emitCardChangeZone);
    }

    void close() {
        pendingMoves.clear();
        libraryOrders.clear();
        publicHandMoves.clear();
        publicRevealSource = null;
        callback = null;
    }

    private SpellAbility resolvingAbility() {
        var stack = observer.getGame().getStack();
        return stack.isResolving() && !stack.isEmpty() ? stack.peekAbility() : null;
    }

    void reveal(List<CardView> cards, ZoneType zone) {
        SpellAbility source = resolvingAbility();
        boolean explore = zone == ZoneType.Library && cards.size() == 1 && source != null
                && source.getApi() == ApiType.Explore && "1".equals(source.getParamOrDefault("Num", "1"));
        for (CardView card : cards) {
            String name = view.disclosedName(card);
            if (name != null && !name.isEmpty()) {
                boolean own = card.getOwner().getId() == observer.getId();
                int alias = view.alias(card.getId());
                boolean token = card.isToken();
                callback.cardsRevealed(own, zone.name(), List.of(alias), List.of(name), List.of(token));
                if (explore) { callback.libraryTopVisible(own, List.of(alias), List.of(name), List.of(token)); }
                if (zone == ZoneType.Library && (explore || publicGroupedDigSource(
                        observer.getGame().getPlayer(card.getOwner()), source))) {
                    if (publicRevealSource != source) { publicHandMoves.clear(); publicRevealSource = source; }
                    // Bind disclosed names only to this public resolution's actual moves.
                    publicHandMoves.put(card.getId(), () -> callback.cardsRevealed(own, ZoneType.Hand.name(),
                            List.of(alias), List.of(name), List.of(token)));
                }
            }
        }
    }

    void libraryTopVisible(Player owner, CardCollectionView cards) {
        libraryTopNotification(owner, cards).run();
    }

    private Runnable libraryTopNotification(Player owner, CardCollectionView cards) {
        List<Integer> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<Boolean> tokens = new ArrayList<>();
        for (Card card : cards) {
            String name = view.visibleName(card.getView());
            if (name == null) { break; }
            ids.add(view.alias(card.getId())); names.add(name); tokens.add(card.isToken());
        }
        boolean own = owner == observer;
        return () -> callback.libraryTopVisible(own, ids, names, tokens);
    }

    void scryChosen(Player owner, CardCollectionView kept, CardCollectionView bottom) {
        List<Card> moves = new ArrayList<>(kept);
        // Forge inserts kept cards at zero in reverse, then appends bottom cards.
        Collections.reverse(moves);
        moves.addAll(bottom);
        if (!moves.isEmpty()) {
            libraryOrders.put(owner.getId(), new PendingLibraryOrder(kept.size(), bottom.size(), ZoneType.Library,
                    moves.stream().map(card -> new LibraryOrderMove(card.getId(), ZoneType.Library)).toList(),
                    libraryOrderNotification(owner, kept, bottom)));
        }
    }

    void surveilChosen(Player owner, CardCollectionView kept, CardCollectionView moved) {
        List<LibraryOrderMove> moves = new ArrayList<>();
        for (Card card : moved) { moves.add(new LibraryOrderMove(card.getId(), ZoneType.Graveyard)); }
        List<Card> reversed = new ArrayList<>(kept);
        Collections.reverse(reversed);
        for (Card card : reversed) { moves.add(new LibraryOrderMove(card.getId(), ZoneType.Library)); }
        if (moves.isEmpty()) { return; }
        var pending = new PendingLibraryOrder(kept.size(), moved.size(), ZoneType.Graveyard, moves,
                libraryOrderNotification(owner, kept, CardCollection.EMPTY));
        pending.started = true;
        libraryOrders.put(owner.getId(), pending);
    }

    void libraryBottomChosen(Player owner, CardCollectionView bottom) {
        if (bottom.isEmpty()) { return; }
        var pending = new PendingLibraryOrder(0, bottom.size(), ZoneType.Library,
                bottom.stream().map(card -> new LibraryOrderMove(card.getId(), ZoneType.Library)).toList(),
                libraryOrderNotification(owner, CardCollection.EMPTY, bottom));
        pending.started = true;
        libraryOrders.put(owner.getId(), pending);
    }

    private Runnable libraryOrderNotification(Player owner, CardCollectionView kept, CardCollectionView bottom) {
        List<Card> cards = new ArrayList<>(kept);
        cards.addAll(bottom);
        int bottomStart = owner.getZone(ZoneType.Library).size() - bottom.size();
        List<Integer> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<Boolean> tokens = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        for (int index = 0; index < cards.size(); index++) {
            Card card = cards.get(index);
            String name = view.visibleName(card.getView());
            if (name == null || name.isEmpty()) { continue; }
            ids.add(view.alias(card.getId())); names.add(name); tokens.add(card.isToken());
            positions.add(index < kept.size() ? index : bottomStart + index - kept.size());
        }
        boolean own = owner == observer;
        return () -> callback.libraryPositionsKnown(own, ids, names, tokens, positions);
    }

    @Subscribe
    public void onScry(GameEventScry event) {
        PendingLibraryOrder pending = libraryOrders.get(event.player().getId());
        if (pending == null) { return; }
        if (pending.started || pending.movedTo != ZoneType.Library
                || pending.topCount != event.toTop() || pending.movedCount != event.toBottom()) {
            libraryOrders.remove(event.player().getId());
        } else { pending.started = true; }
    }

    @Subscribe
    public void onSurveil(GameEventSurveil event) {
        flush();
        PendingLibraryOrder pending = libraryOrders.remove(event.player().getId());
        if (pending != null && pending.movedTo == ZoneType.Graveyard && pending.started
                && pending.topCount == event.toLibrary() && pending.movedCount == event.toGraveyard()
                && pending.moves.isEmpty()) { pending.publish.run(); }
    }

    private boolean consumeLibraryOrderMove(CardView card, ZoneType from, ZoneType to) {
        int owner = card.getOwner().getId();
        PendingLibraryOrder pending = libraryOrders.get(owner);
        if (pending == null || from != ZoneType.Library && to != ZoneType.Library) { return false; }
        if (pending.started && from == ZoneType.Library
                && Objects.equals(pending.moves.peekFirst(), new LibraryOrderMove(card.getId(), to))) {
            pending.moves.removeFirst();
            if (pending.moves.isEmpty() && pending.movedTo == ZoneType.Library) {
                libraryOrders.remove(owner);
                pending.publish.run();
            }
            // Graveyard moves remain public; only library reorders are consumed privately.
            return to == ZoneType.Library;
        }
        libraryOrders.remove(owner);
        return false;
    }

    private boolean canIdentify(CardView card) {
        return view.canIdentify(card);
    }

    private String visibleName(CardView card) {
        return view.visibleName(card);
    }

    private void emitCardChangeZone(PendingMove move) {
        final GameEventCardChangeZone event = move.event();
        final CardView card = event.card();
        if (card == null || card.getOwner() == null) {
            return;
        }
        final boolean ownerIsObserver = card.getOwner().getId() == observer.getId();
        final ZoneType from = zoneType(event.from());
        final ZoneType to = zoneType(event.to());
        if (to == ZoneType.Command && from != ZoneType.Command) {
            final Card effect = observer.getGame().findById(card.getId());
            if (effect != null && effect.isImmutable() && !effect.isEmblem()) {
                final Card source = effect.getEffectSource();
                if (source != null && canIdentify(source.getView())) {
                    callback.publicEffectCreated(ObservationReader.commandEffect(effect, view));
                }
            }
            return;
        }
        final Card current = observer.getGame().findById(card.getId());
        final CardView destination = current == null ? null : current.getView();
        final String name = destination != null && destination.getZone() == to
                && canIdentify(destination) ? visibleName(destination) : move.sourceName();
        final boolean identifiedMove = name != null && !name.isEmpty();
        final boolean trackedMove = identifiedMove || isPublic(from);
        if (!identifiedMove) {
            if (from != null && from.isHidden()) {
                view.conceal(card.getId());
            }
            if (trackedMove) {
                callback.cardZoneChanged(view.alias(card.getId()), null, ownerIsObserver,
                        card.isToken(), zoneName(from), zoneName(to));
            } else {
                callback.anonymousZoneChange(ownerIsObserver, zoneName(from), zoneName(to));
            }
            return;
        }
        callback.cardZoneChanged(
                view.alias(card.getId()), name,
                ownerIsObserver, card.isToken(),
                zoneName(from), zoneName(to));
    }

    @Subscribe
    public void onZoneChanged(GameEventZone event) {
        // Immutable effects can disappear without a GameEventCardChangeZone.
        if (event.zoneType() == ZoneType.Command
                && event.mode() == EventValueChangeType.Removed && event.card() != null) {
            callback.publicEffectRemoved(view.alias(event.card().getId()));
        }
    }

    @Subscribe
    public void onCardModeChosen(GameEventCardModeChosen event) {
        if (event.log()) {
            callback.publicChoice(isObserver(event.player()), event.cardName(), event.mode(),
                    observer.getGame().getPhaseHandler().getTurn());
        }
    }

    @Subscribe
    public void onSpellAbilityCast(GameEventSpellAbilityCast event) {
        flush();
        if (event.si() == null) { return; }
        for (var item : observer.getGame().getStack()) {
            if (item.getId() != event.si().getId()) { continue; }
            var ability = item.getSpellAbility();
            if (ability.isCopied() || ability.isTrigger()) { return; }
            action(ability.isSpell() ? "cast" : "activate", event.si().getActivatingPlayer(),
                    List.of(event.si().getSourceCard()));
            return;
        }
    }

    @Subscribe
    public void onLandPlayed(GameEventLandPlayed event) {
        flush();
        action("play_land", event.player(), List.of(event.land()));
    }

    @Subscribe
    public void onAttackersDeclared(GameEventAttackersDeclared event) {
        flush();
        if (event.attackersMap().isEmpty()
                && !CombatUtil.canAttack(observer.getGame().getPlayer(event.player()))) { return; }
        action("attack", event.player(), event.attackersMap().values());
    }

    @Subscribe
    public void onBlockersDeclared(GameEventBlockersDeclared event) {
        flush();
        if (event.blockers().isEmpty()) { return; }
        // Forge represents an unblocked attacker as an attacker -> itself entry.
        var blockers = event.blockers().values().stream().flatMap(map -> map.entries().stream())
                .filter(entry -> !entry.getKey().equals(entry.getValue())).map(Map.Entry::getValue).toList();
        if (blockers.isEmpty() && (observer.getGame().getCombat() == null
                || !CombatUtil.canBlock(observer.getGame().getPlayer(event.defendingPlayer()),
                        observer.getGame().getCombat()))) { return; }
        action("block", event.defendingPlayer(), blockers);
    }

    @Subscribe
    public void onMulligan(GameEventMulligan event) {
        flush();
        action("mulligan", event.player(), List.of());
    }

    @Subscribe
    public void onShuffle(GameEventShuffle event) {
        flush();
        if (event.player() != null) {
            publicHandMoves.clear();
            publicRevealSource = null;
            libraryOrders.remove(event.player().getId());
            callback.playerShuffled(event.player().getId() == observer.getId());
            for (Card card : observer.getGame().getPlayer(event.player()).getCardsIn(ZoneType.Library)) {
                view.conceal(card.getId());
            }
        }
    }

    private static ZoneType zoneType(ZoneView zone) {
        return zone == null ? null : zone.zoneType();
    }

    private static boolean isPublic(ZoneType zone) {
        return zone != null && zone.isKnown();
    }

    private static String zoneName(ZoneType zone) {
        return zone == null ? null : zone.name();
    }

    private boolean isObserver(PlayerView player) {
        return player != null && player.getId() == observer.getId();
    }

    private void action(String kind, PlayerView player, Collection<CardView> cards) {
        callback.publicEvent(kind, isObserver(player), cards.stream().map(view::referenceId)
                .filter(Objects::nonNull).distinct().sorted().toList());
    }
    static boolean publicGroupedDigSource(Player chooser, SpellAbility source) {
        // shortcut: public, disjoint Creature/Land groups; qualify other DigMultiple forms separately.
        return source != null && source.getApi() == ApiType.DigMultiple
                && chooser.getGame().getStack().isResolving() && source.getActivatingPlayer() == chooser
                && source.hasParam("Reveal") && source.hasParam("Optional")
                && "Creature,Land".equals(source.getParam("ChangeValid"))
                && "Library".equals(source.getParamOrDefault("SourceZone", "Library"))
                && ("Hand".equals(source.getParamOrDefault("DestinationZone", "Hand"))
                        && "Graveyard".equals(source.getParam("DestinationZone2"))
                    || "Battlefield".equals(source.getParam("DestinationZone"))
                        && "Library".equals(source.getParamOrDefault("DestinationZone2", "Library"))
                        && source.hasParam("RestRandomOrder"))
                && List.of("ChooseAmount", "ChosenZone", "ChangeLater").stream().noneMatch(source::hasParam);
    }

}
