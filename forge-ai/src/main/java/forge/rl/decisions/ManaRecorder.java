package forge.rl.decisions;

import forge.game.spellability.SpellAbility;
import java.util.List;

public record ManaRecorder(ManaRequest request, List<SpellAbility> selected) implements DecisionRecorder {
    @Override public List<Integer> encode() {
        if (selected == null) { return List.of(0); }
        if (!isBoundManaChoice()) {
            throw new IllegalArgumentException("Native mana decision outside request bindings");
        }
        return List.of(1);
    }

    private boolean isBoundManaChoice() {
        return request.choice != null && selected.size() == 1 && selected.get(0) == request.choice.ability
                && selected.get(0).getHostCard() == request.choice.card;
    }
}
