package forge.rl;

import java.util.List;

/** The agent answers the current native request, never a precomputed action draft. */
public interface IPlayerDecisionCallback {
    List<Integer> choose(NativeDecision request);

    default void valueNotified(forge.game.player.Player player, forge.game.spellability.SpellAbility source,
            forge.game.GameObject related, String value, String message, String title) { }

    default void cardsRevealed(boolean own, String zone, List<Integer> ids, List<String> names, List<Boolean> tokens) { }
    default void libraryTopVisible(boolean own, List<Integer> ids, List<String> names, List<Boolean> tokens) { }
}
