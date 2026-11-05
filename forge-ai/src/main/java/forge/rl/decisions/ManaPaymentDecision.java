package forge.rl.decisions;

import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.card.mana.ManaCost;
import forge.game.cost.CostPartMana;
import forge.game.mana.ManaConversionMatrix;
import forge.rl.StateReader;
import forge.rl.requests.ManaPaymentRequest;

/** Inputs captured before preparing the native request with the recorder's reader. */
public record ManaPaymentDecision(ManaCost cost, CostPartMana part, SpellAbility root, ManaConversionMatrix matrix, boolean effect) implements Decision<ManaPaymentRequest> {
    @Override public ManaPaymentRequest prepare(Player player, StateReader reader) {
        return new ManaPaymentRequest(reader, player, cost, part, root, matrix, effect);
    }
}
