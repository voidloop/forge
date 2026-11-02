package forge.rl.decisions;

import forge.game.card.Card;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record CombatDamageExecutor(CombatDamageDecision request, List<Integer> answer) implements DecisionExecutor<Map<Card, Integer>> {
    @Override public Map<Card, Integer> apply() {
        request.validate(answer);
        Map<Card, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < answer.size(); i += 2) {
            result.put(request.targets.get(answer.get(i)), answer.get(i + 1));
        }
        return result;
    }
}
