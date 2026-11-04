package forge.rl.decisions;

import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

public record PriorityTargetExecutor(PriorityQuestion.Choice request, Player player, SpellAbility selected) implements DecisionExecutor<Boolean> {
    @Override public Boolean apply() {
        if (isUnboundTargetRequest()) {
            throw new IllegalArgumentException("Unbound ordinary target request");
        }
        if (request.targetIndex == -1) { return false; }
        GameEntity target = request.targets.get(request.targetIndex);
        if (isSelectedTargetIllegal(target)) {
            throw new IllegalArgumentException("Selected target is no longer legal");
        }
        request.ability.getTargets().add(target);
        return true;
    }

    private boolean isUnboundTargetRequest() {
        return selected != request.ability || request.targets == null || !request.ability.getTargets().isEmpty();
    }

    private boolean isSelectedTargetIllegal(GameEntity target) {
        return target instanceof Card card && player.getGame().getCardState(card, null) != card
                || !request.ability.canTarget(target);
    }
}
