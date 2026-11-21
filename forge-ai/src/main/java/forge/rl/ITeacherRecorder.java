package forge.rl;

import java.util.Map;

/** Receives detached pre-choice state and original AI choices immediately at selection. */
@FunctionalInterface
public interface ITeacherRecorder {
    void record(Map<String, Object> example);
}
