package forge.rl.decisions;

import forge.game.spellability.SpellAbility;
import java.util.List;

public record PriorityRecorder(PriorityDecision request, List<SpellAbility> selected) implements DecisionRecorder {
    @Override public List<Integer> encode() {
        if (selected == null) { return List.of(0); }
        if (selected.size() == 1) {
            for (int i = 0; i < request.bindings.size(); i++) {
                PriorityDecision.Choice choice = request.bindings.get(i);
                if (isChosenAbility(choice)) {
                    return encodeChoice(choice, i);
                }
            }
        }
        throw new IllegalArgumentException("Native priority response outside exact bindings");
    }

    private boolean isChosenAbility(PriorityDecision.Choice choice) {
        return selected.get(0) == choice.ability && selected.get(0).getHostCard() == choice.card;
    }

    private List<Integer> encodeChoice(PriorityDecision.Choice choice, int index) {
        if (choice.targets == null) { return List.of(index + 1); }
        if (choice.ability.getTargets().size() == 1) {
            for (int j = 0; j < choice.targets.size(); j++) {
                if (choice.ability.getTargets().get(0) == choice.targets.get(j)) { return List.of(index + 1, j); }
            }
        }
        throw new IllegalArgumentException("Native final target outside previous bindings");
    }
}
