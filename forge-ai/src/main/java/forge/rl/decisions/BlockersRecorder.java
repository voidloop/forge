package forge.rl.decisions;

import forge.game.card.Card;
import java.util.ArrayList;
import java.util.List;

public record BlockersRecorder() implements DecisionRecorder<BlockersRequest, Void> {
    @Override public List<Integer> encode(BlockersRequest request, Void ignored) {
        request.requireContext(); request.requireAttackers();
        var answer = new ArrayList<Integer>();
        for (int a = 0; a < request.attackers.size(); a++) {
            for (Card card : request.combat.getBlockers(request.attackers.get(a))) {
                int b = request.blockers.indexOf(card);
                if (!isBoundBlocker(request, b, card)) {
                    throw new IllegalArgumentException("Final block declaration outside exact bindings");
                }
                answer.add(b); answer.add(a);
            }
        }
        request.validate(answer); request.validateNative();
        return List.copyOf(answer);
    }

    private boolean isBoundBlocker(BlockersRequest request, int index, Card blocker) {
        return index >= 0 && request.blockers.get(index) == blocker;
    }
}
