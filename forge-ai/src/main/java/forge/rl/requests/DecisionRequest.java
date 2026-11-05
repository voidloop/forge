package forge.rl.requests;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Detached question data shared by native decision requests. */
public interface DecisionRequest {
    Map<String, Object> data();
    default List<Integer> encodeAnswer(Object answer) {
        throw new UnsupportedOperationException("Encoding not implemented for " + getClass().getSimpleName());
    }

    static DecisionRequest create(Map<String, Object> data, Function<Object, List<Integer>> answerEncoder) {
        Objects.requireNonNull(data);
        Objects.requireNonNull(answerEncoder);
        return new DecisionRequest() {
            @Override public Map<String, Object> data() { return data; }
            @Override public List<Integer> encodeAnswer(Object answer) { return answerEncoder.apply(answer); }
        };
    }
}
