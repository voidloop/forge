package forge.rl;

import com.google.common.eventbus.Subscribe;
import com.google.common.collect.Multimap;
import forge.card.CardStateName;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.card.CardView.CardStateView;
import forge.game.event.GameEventAttackersDeclared;
import forge.game.event.GameEventBlockersDeclared;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventLandPlayed;
import forge.game.event.GameEventMulligan;
import forge.game.event.GameEventShuffle;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventTurnEnded;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;
import forge.game.zone.ZoneView;

import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** Sends only facts visible to the RL player across the Java/Python boundary. */
public final class KnowledgeEventObserver {
    private final Player observer;
    private final IKnowledgeEventCallback callback;
    private final Set<Integer> knownOpponentCards = new HashSet<>();

    public KnowledgeEventObserver(Player observer, IKnowledgeEventCallback callback) {
        this.observer = observer;
        this.callback = callback;
    }

    @Subscribe
    public void onCardChangeZone(GameEventCardChangeZone event) {
        final CardView card = event.card();
        if (card == null || card.getOwner() == null) {
            return;
        }
        final boolean ownerIsObserver = card.getOwner().getId() == observer.getId();
        final ZoneType from = zoneType(event.from());
        final ZoneType to = zoneType(event.to());
        final boolean publicMove = isPublic(from) || isPublic(to);
        // Remembering a card does not identify a later hidden move (e.g. a draw
        // after shuffling). History must not link that move to the remembered card.
        final boolean identifiedMove = ownerIsObserver || publicMove;
        callback.publicEvent(
                "zone_change", ownerIsObserver, identifiedMove ? card.getId() : null,
                zoneName(from), zoneName(to), null);
        if (!ownerIsObserver && !publicMove) {
            callback.anonymousZoneChange(false, zoneName(from), zoneName(to));
            return;
        }
        if (!card.canFaceDownBeShownTo(observer.getView())) {
            return;
        }
        if (!ownerIsObserver) {
            knownOpponentCards.add(card.getId());
        }
        // A face-down card the observer may look at is identified by its face-up state.
        final CardStateView faceUp = card.isFaceDown() ? card.getAlternateState() : null;
        if (card.isFaceDown() && faceUp == null) {
            return;
        }
        final String name = faceUp == null ? printedName(card) : faceUp.getOracleName();
        // The game card may already be face down while the event view is not yet.
        if (name.isEmpty()) {
            return;
        }
        callback.cardZoneChanged(
                card.getId(), name,
                ownerIsObserver, card.isToken(),
                zoneName(from), zoneName(to));
    }

    @Subscribe
    public void onSpellAbilityCast(GameEventSpellAbilityCast event) {
        final CardView card = event.si() == null ? null : event.si().getSourceCard();
        final PlayerView player = event.si() == null ? null : event.si().getActivatingPlayer();
        final String kind = event.sa().isSpell() ? "cast"
                : event.si() != null && event.si().isTrigger() ? "trigger" : "activate";
        callback.publicEvent(kind, isObserver(player), referenceId(card), null, null, null);
    }

    @Subscribe
    public void onLandPlayed(GameEventLandPlayed event) {
        callback.publicEvent(
                "play_land", isObserver(event.player()), referenceId(event.land()),
                null, "Battlefield", null);
    }

    @Subscribe
    public void onAttackersDeclared(GameEventAttackersDeclared event) {
        for (CardView attacker : event.attackersMap().values()) {
            callback.publicEvent(
                    "attack", isObserver(event.player()), referenceId(attacker),
                    null, null, null);
        }
    }

    @Subscribe
    public void onBlockersDeclared(GameEventBlockersDeclared event) {
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
        callback.publicEvent("mulligan", isObserver(event.player()), null, null, null, null);
    }

    @Subscribe
    public void onTurnEnded(GameEventTurnEnded event) {
        for (Player player : observer.getGame().getPlayers()) {
            callback.publicEvent(
                    "mana_left_open", player.getId() == observer.getId(), null,
                    null, null, untappedManaLands(player));
        }
    }

    /**
     * The card's name in its destination zone: the event view may still show the state it
     * left with, e.g. a transformed back face returning to hand. A Room permanent only has
     * its unlocked doors' name (CR 709.5), so it is reported by its printed card's name.
     */
    private String printedName(CardView view) {
        final Card card = observer.getGame().findById(view.getId());
        if (card == null) {
            return view.getOracleName();
        }
        return card.isRoom() ? card.getName(card.getState(CardStateName.Original)) : card.getName();
    }

    @Subscribe
    public void onShuffle(GameEventShuffle event) {
        if (event.player() != null) {
            callback.playerShuffled(event.player().getId() == observer.getId());
        }
    }

    private static ZoneType zoneType(ZoneView zone) {
        return zone == null ? null : zone.zoneType();
    }

    private static boolean isPublic(ZoneType zone) {
        return zone == ZoneType.Battlefield || zone == ZoneType.Graveyard
                || zone == ZoneType.Exile || zone == ZoneType.Stack;
    }

    private static String zoneName(ZoneType zone) {
        return zone == null ? null : zone.name();
    }

    private boolean isObserver(PlayerView player) {
        return player != null && player.getId() == observer.getId();
    }

    private Integer referenceId(CardView card) {
        return card != null && card.canBeShownTo(observer.getView()) ? card.getId() : null;
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
