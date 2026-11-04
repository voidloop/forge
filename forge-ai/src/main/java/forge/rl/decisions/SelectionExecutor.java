package forge.rl.decisions;

import forge.game.GameEntity;
import java.util.List;

public record SelectionExecutor<T extends GameEntity>(SelectionQuestion<T> request, List<Integer> selected) implements DecisionExecutor<List<T>> {
    @Override public List<T> apply() {
        request.validate(selected);
        return selected.stream().map(request.bindings::get).toList();
    }
}
