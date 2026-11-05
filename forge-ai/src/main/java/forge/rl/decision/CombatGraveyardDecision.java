package forge.rl.decision;

import forge.game.player.Player;
import forge.game.card.CardCollectionView;
import forge.game.zone.ZoneType;
import forge.game.spellability.SpellAbility;
import forge.rl.StateReader;
import forge.rl.decision.context.CombatGraveyardContext;

/** Inputs captured before preparing the native request with the recorder's reader. */
public record CombatGraveyardDecision(CardCollectionView cards, ZoneType destination, SpellAbility source) implements Decision<CombatGraveyardContext> {
    @Override public CombatGraveyardContext prepare(Player player, StateReader reader) {
        return new CombatGraveyardContext(reader, player, cards, destination, source);
    }
}
