package forge.rl.decision.context;

import forge.game.card.CardCollection;
import java.util.List;

public record CombatGraveyardExecutor(CombatGraveyardContext request, List<Integer> answer) implements DecisionExecutor<CardCollection> {
    @Override public CardCollection apply() {
        request.requireContext();
        return new CardCollection(new SelectionExecutor<>(request.selection, answer).apply());
    }
}
