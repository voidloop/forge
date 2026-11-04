package forge.rl.decisions;

import java.util.List;

public record ManaColorExecutor(ManaRequest.Choice request, List<Integer> answer) implements DecisionExecutor<Byte> {
    @Override public Byte apply() {
        var options = request.colors().stream().toList();
        request.color = options.get(ManaRequest.index(answer, options.size())).getColorMask();
        return request.color;
    }
}
