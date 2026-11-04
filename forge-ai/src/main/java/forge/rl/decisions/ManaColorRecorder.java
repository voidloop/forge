package forge.rl.decisions;

import java.util.List;

public record ManaColorRecorder() implements DecisionRecorder<ManaQuestion.ColorRequest, Byte> {
    @Override public List<Integer> encode(ManaQuestion.ColorRequest request, Byte chosen) {
        var choice = request.choice();
        var options = choice.colors().stream().toList();
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).getColorMask() == chosen) { choice.color = chosen; return List.of(i); }
        }
        throw new IllegalArgumentException("Native mana color outside request options");
    }
}
