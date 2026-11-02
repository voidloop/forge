package forge.rl.decisions;

import forge.game.spellability.SpellAbility;
import java.util.List;

public record ManaRecorder() implements DecisionRecorder<ManaRequest, List<SpellAbility>> {
    @Override public List<Integer> encode(ManaRequest request, List<SpellAbility> selected) {
        if (selected == null) { return List.of(0); }
        if (!isBoundManaChoice(request, selected)) {
            throw new IllegalArgumentException("Native mana decision outside request bindings");
        }
        return List.of(1);
    }

    private boolean isBoundManaChoice(ManaRequest request, List<SpellAbility> selected) {
        return request.choice != null && selected.size() == 1 && selected.get(0) == request.choice.ability
                && selected.get(0).getHostCard() == request.choice.card;
    }
}
