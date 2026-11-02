package forge.rl.decisions;

import java.util.List;

public record ManaColorRecorder(ManaDecision.Choice request, byte chosen) implements DecisionRecorder {
    @Override public List<Integer> encode() {
        var options = request.colors().stream().toList();
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).getColorMask() == chosen) { request.color = chosen; return List.of(i); }
        }
        throw new IllegalArgumentException("Native mana color outside request options");
    }
}
