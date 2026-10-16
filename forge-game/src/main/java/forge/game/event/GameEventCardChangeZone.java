package forge.game.event;

import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.player.PlayerView;
import forge.game.zone.Zone;
import forge.game.zone.ZoneView;
import forge.util.TextUtil;
import java.util.Set;

public record GameEventCardChangeZone(CardView card, ZoneView from, ZoneView to,
                                      Set<PlayerView> sourceViewers,
                                      Integer drawPosition) implements GameEvent {

    public GameEventCardChangeZone(Card card, Zone zoneFrom, Zone zoneTo) {
        this(card, zoneFrom, zoneTo, Set.of());
    }

    public GameEventCardChangeZone(Card card, Zone zoneFrom, Zone zoneTo,
                                   Set<PlayerView> sourceViewers) {
        this(card, zoneFrom, zoneTo, sourceViewers, null);
    }

    public GameEventCardChangeZone(Card card, Zone zoneFrom, Zone zoneTo,
                                   Set<PlayerView> sourceViewers, Integer drawPosition) {
        this(CardView.get(card),
             zoneFrom == null ? null : zoneFrom.getView(),
             zoneTo == null ? null : zoneTo.getView(), Set.copyOf(sourceViewers), drawPosition);
    }

    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        final String fromStr = from != null ? "" + from.zoneType() : "null";
        final String toStr = to != null ? "" + to.zoneType() : "null";
        return TextUtil.concatWithSpace("" + card, ":", TextUtil.enclosedBracket(fromStr), "->", TextUtil.enclosedBracket(toStr));
    }
}
