package forge.rl.decisions;

import forge.rl.StateReader;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.List;
import java.util.Map;

/** Owner's order for simultaneous vanilla combat deaths, before the native zone moves. */
public final class CombatGraveyardDecision {
    public final Map<String, Object> data;
    private final Player player;
    private final Combat combat;
    private final List<Card> cards;
    private final DecisionRequest<Card> selection;

    public CombatGraveyardDecision(Player player, StateReader view, CardCollectionView cards,
            ZoneType destination, SpellAbility source) {
        this.player = player;
        this.combat = player.getGame().getCombat();
        this.cards = List.copyOf(cards);
        if (destination != ZoneType.Graveyard || source != null || cards.size() < 2) {
            throw new UnsupportedOperationException("Only simultaneous combat graveyard ordering");
        }
        requireContext();
        selection = new DecisionRequest<>(player, view, "combat_graveyard_order", cards,
                "Order simultaneous combat deaths into graveyard", cards.size(), cards.size(), false, null, null, null);
        data = selection.data;
    }

    private void requireContext() {
        if (player.getGame().getCombat() != combat) {
            throw new IllegalArgumentException("Combat changed during graveyard ordering");
        }
        CombatDamageDecision.requireScope(player);
        for (Card card : cards) {
            if (card.getOwner() != player || card.getController() != player
                    || player.getGame().getCardState(card, null) != card || !card.isInZone(ZoneType.Battlefield)
                    || !card.isCreature() || card.getDamage() < card.getNetToughness()) {
                throw new IllegalArgumentException("Unbound or stale simultaneous combat death");
            }
        }
    }

    public CardCollection apply(List<Integer> answer) {
        requireContext();
        return new CardCollection(selection.apply(answer));
    }

    public List<Integer> encode(CardCollectionView chosen) {
        requireContext();
        return selection.encode(chosen);
    }
}
