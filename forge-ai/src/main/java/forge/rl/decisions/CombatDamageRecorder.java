package forge.rl.decisions;

import forge.game.card.Card;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record CombatDamageRecorder() implements DecisionRecorder<CombatDamageRequest, Map<Card, Integer>> {
    @Override public List<Integer> encode(CombatDamageRequest request, Map<Card, Integer> chosen) {
        if (chosen == null) { throw new UnsupportedOperationException("Deferred combat damage outside vanilla scope"); }
        var answer = new ArrayList<Integer>();
        for (var entry : chosen.entrySet()) {
            int index = entry.getKey() == null ? -1 : request.targets.indexOf(entry.getKey());
            if (!isBoundDamageEntry(request, entry, index)) {
                throw new IllegalArgumentException("Final damage map outside exact bindings");
            }
        }
        for (int i = 0; i < request.targets.size(); i++) {
            if (chosen.containsKey(request.targets.get(i))) { answer.add(i); answer.add(chosen.get(request.targets.get(i))); }
        }
        request.validate(answer);
        return List.copyOf(answer);
    }

    private boolean isBoundDamageEntry(CombatDamageRequest request, Map.Entry<Card, Integer> entry, int index) {
        return index >= 0 && request.targets.get(index) == entry.getKey() && entry.getValue() != null;
    }
}
