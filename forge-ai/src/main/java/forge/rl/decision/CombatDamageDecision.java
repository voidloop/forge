package forge.rl.decision;

import forge.game.player.Player;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.GameEntity;
import forge.rl.StateReader;
import forge.rl.decision.context.CombatDamageContext;

/** Inputs captured before preparing the native request with the recorder's reader. */
public record CombatDamageDecision(Card source, CardCollectionView targets, CardCollectionView remaining, int damage, GameEntity defender, boolean overrideOrder) implements Decision<CombatDamageContext> {
    @Override public CombatDamageContext prepare(Player player, StateReader reader) {
        return new CombatDamageContext(reader, player, source, targets, remaining, damage, defender, overrideOrder);
    }
}
