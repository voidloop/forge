package forge.rl;

import forge.game.GameObject;
import forge.game.spellability.SpellAbility;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable description of one fully specified RL action candidate.
 *
 * The initial diagnostic uses one explicit target for a single-target Attach
 * ability. Modes, variable values and optional costs can be added without
 * changing the controller's candidate abstraction.
 */
public final class RlActionCandidate {
    private final SpellAbility ability;
    private final List<GameObject> targets;

    public RlActionCandidate(SpellAbility ability, List<GameObject> targets) {
        if (ability == null) {
            throw new IllegalArgumentException("ability must not be null");
        }
        if (targets == null) {
            throw new IllegalArgumentException("targets must not be null");
        }
        this.ability = ability;
        this.targets = Collections.unmodifiableList(new ArrayList<>(targets));
    }

    public SpellAbility getAbility() {
        return ability;
    }

    public List<GameObject> getTargets() {
        return targets;
    }
}
