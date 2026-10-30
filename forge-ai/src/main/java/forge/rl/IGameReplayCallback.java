package forge.rl;

import java.util.List;

/** Private replay transcript; native identifiers/randomness are not student inputs. */
public interface IGameReplayCallback {
    /** Null records the native answer; otherwise RNG checkpoint followed by the saved answer. */
    List<String> before(String kind, List<String> options);
    void after(List<String> answer, String randomState);
}
