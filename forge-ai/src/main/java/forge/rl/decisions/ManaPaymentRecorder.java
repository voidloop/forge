package forge.rl.decisions;

import forge.game.mana.Mana;
import forge.game.spellability.SpellAbility;
import java.util.ArrayList;
import java.util.List;

public record ManaPaymentRecorder(ManaPaymentRequest request, boolean paid) implements DecisionRecorder {
    @Override public List<Integer> encode() {
        List<Integer> answer = new ArrayList<>(List.of(paid ? 1 : 0, request.root.getPayingManaAbilities().size()));
        for (SpellAbility ability : request.root.getPayingManaAbilities()) {
            int index = request.sources.indexOf(ability);
            if (!request.isBoundSourceAbility(index, ability)) { throw new IllegalArgumentException("Unbound payment ability variant"); }
            answer.add(index);
        }
        for (Mana mana : request.root.getPayingMana()) { answer.add(request.source(mana)); }
        request.validate(answer);
        if (!request.hasExpectedPaymentResources(answer)) {
            throw new IllegalStateException("Unrepresentable native payment resources");
        }
        return List.copyOf(answer);
    }
}
