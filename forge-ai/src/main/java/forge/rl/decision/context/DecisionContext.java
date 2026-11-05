package forge.rl.decision.context;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Prepared native decision context. Only question and answer encodings are exported. */
public interface DecisionContext {
    String kind();

    Map<String, Object> encodeQuestion();
    default List<Integer> encodeAnswer(Object answer) {
        throw new UnsupportedOperationException("Encoding not implemented for " + getClass().getSimpleName());
    }

    static DecisionContext create(String kind, Map<String, Object> data, Function<Object, List<Integer>> answerEncoder) {
        Objects.requireNonNull(data);
        Objects.requireNonNull(answerEncoder);
        return new DecisionContext() {
            @Override public String kind() { return kind; }
            @Override public Map<String, Object> encodeQuestion() { return data; }
            @Override public List<Integer> encodeAnswer(Object answer) { return answerEncoder.apply(answer); }
        };
    }
}
