package forge.rl.decisions;

import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import java.util.List;

public record CombatGraveyardRecorder() implements DecisionRecorder<CombatGraveyardRequest, CardCollectionView> {
    @Override public List<Integer> encode(CombatGraveyardRequest request, CardCollectionView chosen) {
        request.requireContext();
        return new SelectionRecorder<Card>().encode(request.selection, chosen);
    }
}
