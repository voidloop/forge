package forge.rl.decisions;

import forge.game.card.Card;
import java.util.ArrayList;
import java.util.List;

public record BlockersRecorder(BlockersRequest request) implements DecisionRecorder {
    @Override public List<Integer> encode() {
        request.requireContext(); request.requireAttackers();
        var answer = new ArrayList<Integer>();
        for (int a = 0; a < request.attackers.size(); a++) {
            for (Card card : request.combat.getBlockers(request.attackers.get(a))) {
                int b = request.blockers.indexOf(card);
                if (!isBoundBlocker(b, card)) {
                    throw new IllegalArgumentException("Final block declaration outside exact bindings");
                }
                answer.add(b); answer.add(a);
            }
        }
        request.validate(answer); request.validateNative();
        return List.copyOf(answer);
    }

    private boolean isBoundBlocker(int index, Card blocker) {
        return index >= 0 && request.blockers.get(index) == blocker;
    }
}
