package forge.rl.decision.context;

import java.util.List;

public record ManaColorExecutor(ManaContext.Choice request, List<Integer> answer) implements DecisionExecutor<Byte> {
    @Override public Byte apply() {
        var options = request.colors().stream().toList();
        request.color = options.get(ManaContext.index(answer, options.size())).getColorMask();
        return request.color;
    }
}
