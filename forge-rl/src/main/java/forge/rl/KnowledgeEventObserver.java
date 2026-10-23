package forge.rl;

import com.google.common.eventbus.Subscribe;
import com.google.common.collect.Multimap;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.event.GameEventAttackersDeclared;
import forge.game.event.GameEventBlockersDeclared;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventLandPlayed;
import forge.game.event.GameEventMulligan;
import forge.game.event.GameEventShuffle;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventTurnEnded;
import forge.game.event.GameEventZone;
import forge.game.event.EventValueChangeType;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;
import forge.game.zone.ZoneView;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;

/** Sends only facts visible to the RL player across the Java/Python boundary. */
public final class KnowledgeEventObserver {
    private final Player observer;
    private final ObservationFacade view;
    private IKnowledgeEventCallback callback;
    private record PendingMove(GameEventCardChangeZone event, String sourceName) { }
    private final List<PendingMove> pendingMoves = new ArrayList<>();

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
        final List<PendingMove> pending = new ArrayList<>(pendingMoves);
        pendingMoves.clear();
        pending.forEach(this::emitCardChangeZone);
    }

    void close() {
        pendingMoves.clear();
        callback = null;
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
        callback.publicEvent(
                "zone_change", ownerIsObserver, trackedMove ? view.alias(card.getId()) : null,
                zoneName(from), zoneName(to), null);
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
    public void onSpellAbilityCast(GameEventSpellAbilityCast event) {
        flush();
        final CardView card = event.si() == null ? null : event.si().getSourceCard();
        final PlayerView player = event.si() == null ? null : event.si().getActivatingPlayer();
        final String kind = event.sa().isSpell() ? "cast"
                : event.si() != null && event.si().isTrigger() ? "trigger" : "activate";
        callback.publicEvent(kind, isObserver(player), referenceId(card), null, null, null);
    }

    @Subscribe
    public void onLandPlayed(GameEventLandPlayed event) {
        flush();
        callback.publicEvent(
                "play_land", isObserver(event.player()), referenceId(event.land()),
                null, "Battlefield", null);
    }

    @Subscribe
    public void onAttackersDeclared(GameEventAttackersDeclared event) {
        flush();
        for (CardView attacker : event.attackersMap().values()) {
            callback.publicEvent(
                    "attack", isObserver(event.player()), referenceId(attacker),
                    null, null, null);
        }
    }

    @Subscribe
    public void onBlockersDeclared(GameEventBlockersDeclared event) {
        flush();
        for (Map.Entry<?, Multimap<CardView, CardView>> entry
                : event.blockers().entrySet()) {
            for (CardView blocker : entry.getValue().values()) {
                callback.publicEvent(
                        "block", isObserver(event.defendingPlayer()), referenceId(blocker),
                        null, null, null);
            }
        }
    }

    @Subscribe
    public void onMulligan(GameEventMulligan event) {
        flush();
        callback.publicEvent("mulligan", isObserver(event.player()), null, null, null, null);
    }

    @Subscribe
    public void onTurnEnded(GameEventTurnEnded event) {
        flush();
        for (Player player : observer.getGame().getPlayers()) {
            callback.publicEvent(
                    "mana_left_open", player.getId() == observer.getId(), null,
                    null, null, untappedManaLands(player));
        }
    }

    @Subscribe
    public void onShuffle(GameEventShuffle event) {
        flush();
        if (event.player() != null) {
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

    private Integer referenceId(CardView card) {
        return view.referenceId(card);
    }

    private static int untappedManaLands(Player player) {
        int total = 0;
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (card.isLand() && !card.isTapped() && !card.getManaAbilities().isEmpty()) {
                total += 1;
            }
        }
        return total;
    }
}
