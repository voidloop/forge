package forge.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardCopyService;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/** Provenance of resolved Pump modifiers; native tables and callbacks remain authoritative. */
public final class ResolvedContinuousEffect {
    private final long timestamp;
    private final Card source;
    private final long sourceGameTimestamp;
    private final String description;
    private final List<Player> sourceViewers = new ArrayList<>();
    private final List<Player> viewers = new ArrayList<>();
    private final Player controller;
    private final String duration;
    private final Map<Card, Long> affectedCards = new LinkedHashMap<>();
    private final List<Player> affectedPlayers = new ArrayList<>();

    ResolvedContinuousEffect(final SpellAbility ability, final long timestamp) {
        this.timestamp = timestamp;
        final Card host = ability.getHostCard();
        for (Player viewer : host.getGame().getPlayers()) {
            if (host.getView().canBeShownTo(viewer.getView())
                    && host.getView().canFaceDownBeShownTo(viewer.getView())) {
                sourceViewers.add(viewer);
            }
        }
        source = CardCopyService.getLKICopy(host);
        sourceGameTimestamp = source.getGameTimestamp();
        description = ability.getDescription();
        controller = ability.getActivatingPlayer();
        duration = ability.getParamOrDefault("Duration", "UntilEndOfTurn");
    }

    public long getTimestamp() {
        return timestamp;
    }

    public Card getSource() {
        return source;
    }

    public long getSourceGameTimestamp() {
        return sourceGameTimestamp;
    }

    public String getDescription() {
        return description;
    }

    public boolean isSourceVisibleTo(final Player observer) {
        return sourceViewers.contains(observer);
    }

    public boolean isVisibleTo(final Player observer) {
        return viewers.contains(observer);
    }

    public Player getController() {
        return controller;
    }

    public String getDuration() {
        return duration;
    }

    public CardCollectionView getAffectedCards() {
        final CardCollection current = new CardCollection();
        for (final Map.Entry<Card, Long> entry : affectedCards.entrySet()) {
            final Card card = source.getGame().getCardState(entry.getKey(), null);
            if (card != null && card.getGameTimestamp() == entry.getValue()) {
                current.add(card);
            }
        }
        return current;
    }

    public List<Player> getAffectedPlayers() {
        return List.copyOf(affectedPlayers);
    }

    void addAffected(final GameEntity entity) {
        if (entity instanceof Card card) {
            affectedCards.put(card, card.getGameTimestamp());
        } else if (entity instanceof Player player && !affectedPlayers.contains(player)) {
            affectedPlayers.add(player);
        }
        for (Player observer : sourceViewers) {
            final boolean published = entity instanceof Player
                    || entity instanceof Card card && (card.isInZone(ZoneType.Battlefield)
                    || card.isInZone(ZoneType.Stack)
                    || card.getView().canBeShownTo(observer.getView())
                    && card.getView().canFaceDownBeShownTo(observer.getView()));
            if (published && !viewers.contains(observer)) {
                viewers.add(observer);
            }
        }
    }

    boolean removeAffected(final GameEntity entity) {
        affectedCards.remove(entity);
        affectedPlayers.remove(entity);
        return affectedCards.isEmpty() && affectedPlayers.isEmpty();
    }
}
