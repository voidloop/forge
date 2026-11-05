package forge.rl.decision.context;

import java.util.List;

public record TriggerExecutor(TriggerContext request, List<Integer> answer) implements DecisionExecutor<Void> {
    @Override public Void apply() {
        if (!request.isPermutation(answer)) { throw new IllegalArgumentException("Mandatory ETB preparation answer"); }
        request.requireLiveSources();
        if (hasStaleStack()) {
            throw new IllegalArgumentException("Stale ETB preparation");
        }
        for (int index : answer) { request.player.getGame().getStack().add(request.bindings.get(index).wrapper()); }
        request.preparedStack();
        return null;
    }

    private boolean hasStaleStack() {
        return !TriggerContext.sameAbilities(TriggerContext.stack(request.player), request.stackBefore);
    }
}
