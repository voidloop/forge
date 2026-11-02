package forge.rl.decisions;

import forge.game.card.CardCollectionView;
import java.util.List;

public record CombatGraveyardRecorder(CombatGraveyardDecision request, CardCollectionView chosen) implements DecisionRecorder {
    @Override public List<Integer> encode() {
        request.requireContext();
        return new SelectionRecorder<>(request.selection, chosen).encode();
    }
}
