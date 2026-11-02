package forge.rl.decisions;

import forge.game.spellability.SpellAbility;
import java.util.List;

public record PriorityRecorder() implements DecisionRecorder<PriorityRequest, List<SpellAbility>> {
    @Override public List<Integer> encode(PriorityRequest request, List<SpellAbility> selected) {
        if (selected == null) { return List.of(0); }
        if (selected.size() == 1) {
            for (int i = 0; i < request.bindings.size(); i++) {
                PriorityRequest.Choice choice = request.bindings.get(i);
                if (isChosenAbility(choice, selected)) {
                    return encodeChoice(choice, i);
                }
            }
        }
        throw new IllegalArgumentException("Native priority response outside exact bindings");
    }

    private boolean isChosenAbility(PriorityRequest.Choice choice, List<SpellAbility> selected) {
        return selected.get(0) == choice.ability && selected.get(0).getHostCard() == choice.card;
    }

    private List<Integer> encodeChoice(PriorityRequest.Choice choice, int index) {
        if (choice.targets == null) { return List.of(index + 1); }
        if (choice.ability.getTargets().size() == 1) {
            for (int j = 0; j < choice.targets.size(); j++) {
                if (choice.ability.getTargets().get(0) == choice.targets.get(j)) { return List.of(index + 1, j); }
            }
        }
        throw new IllegalArgumentException("Native final target outside previous bindings");
    }
}
