package forge.rl;

import forge.ai.AiPlayDecision;
import forge.game.spellability.Spell;

/** Native observation only: callbacks must not mutate the spell or rerun the AI.
 * Copy any required facts during the callback; these live objects are not training inputs.
 */
public interface EffectCastProbe {
    void beforeDecision(Spell spell, boolean mandatory, boolean withoutMana);
    void decided(Spell spell, AiPlayDecision decision);
    /** Native cast outcome, separate from willingness. Exceptions propagate without this callback. */
    void completed(Spell spell, boolean cast);
}
