package forge.rl;

import com.google.common.eventbus.Subscribe;
import forge.card.CardStateName;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.card.CardView.CardStateView;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventShuffle;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.game.zone.ZoneView;

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
        if (!card.canFaceDownBeShownTo(observer.getView())) {
            return;
        }
        if (!ownerIsObserver && !publicMove && !knownOpponentCards.contains(card.getId())) {
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
        callback.cardZoneChanged(
                card.getId(), faceUp == null ? printedName(card) : faceUp.getOracleName(),
                ownerIsObserver, card.isToken(),
                zoneName(from), zoneName(to));
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
}
