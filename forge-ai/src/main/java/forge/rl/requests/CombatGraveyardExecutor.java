package forge.rl.requests;

import forge.game.card.CardCollection;
import java.util.List;

public record CombatGraveyardExecutor(CombatGraveyardRequest request, List<Integer> answer) implements DecisionExecutor<CardCollection> {
    @Override public CardCollection apply() {
        request.requireContext();
        return new CardCollection(new SelectionExecutor<>(request.selection, answer).apply());
    }
}
