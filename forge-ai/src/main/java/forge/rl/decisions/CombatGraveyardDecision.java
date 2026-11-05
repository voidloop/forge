package forge.rl.decisions;

import forge.game.player.Player;
import forge.game.card.CardCollectionView;
import forge.game.zone.ZoneType;
import forge.game.spellability.SpellAbility;
import forge.rl.StateReader;
import forge.rl.requests.CombatGraveyardRequest;

/** Inputs captured before preparing the native request with the recorder's reader. */
public record CombatGraveyardDecision(CardCollectionView cards, ZoneType destination, SpellAbility source) implements Decision<CombatGraveyardRequest> {
    @Override public CombatGraveyardRequest prepare(Player player, StateReader reader) {
        return new CombatGraveyardRequest(reader, player, cards, destination, source);
    }
}
