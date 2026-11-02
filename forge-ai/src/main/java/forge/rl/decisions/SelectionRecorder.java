package forge.rl.decisions;

import forge.game.GameEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record SelectionRecorder<T extends GameEntity>(DecisionRequest<T> request, Iterable<T> selected) implements DecisionRecorder {
    @Override public List<Integer> encode() {
        Objects.requireNonNull(selected, "Missing native selection");
        List<Integer> indices = new ArrayList<>();
        for (T entity : selected) {
            int index = -1;
            for (int i = 0; i < request.bindings.size(); i++) {
                if (request.bindings.get(i) == entity) { index = i; break; }
            }
            if (index < 0) { throw new IllegalArgumentException("Native selection outside request bindings"); }
            indices.add(index);
        }
        request.validate(indices);
        return List.copyOf(indices);
    }
}
