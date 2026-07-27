package forge.rl;

import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.ability.ApiType;
import forge.game.spellability.SpellAbility;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Rules-only candidate enumeration for the staged Forge-AI-to-RL migration.
 */
public final class RlActionCandidateEnumerator {
    private RlActionCandidateEnumerator() {
    }

    public static boolean supportsSingleTargetAttach(SpellAbility ability) {
        return findSingleTargetAttach(ability) != null;
    }

    public static List<RlActionCandidate> enumerateSingleTargetAttach(
            SpellAbility ability) {
        SpellAbility diagnosticCopy = ability.copy();
        if (diagnosticCopy == null) {
            return List.of();
        }

        SpellAbility attach = findSingleTargetAttach(diagnosticCopy);
        if (attach == null) {
            return List.of();
        }
        attach.resetTargets();

        List<GameEntity> legalTargets = new ArrayList<>(
                attach.getTargetRestrictions().getAllCandidates(attach));
        legalTargets.sort(Comparator
                .comparing((GameEntity target) -> target.getClass().getName())
                .thenComparingInt(GameEntity::getId));

        List<RlActionCandidate> candidates = new ArrayList<>(legalTargets.size());
        for (GameEntity target : legalTargets) {
            candidates.add(
                    new RlActionCandidate(ability, List.<GameObject>of(target)));
        }
        return candidates;
    }

    private static SpellAbility findSingleTargetAttach(SpellAbility ability) {
        SpellAbility current = ability;
        while (current != null) {
            if (current.getApi() == ApiType.Attach && current.usesTargeting()) {
                if (current.getMinTargets() == 1 && current.getMaxTargets() == 1) {
                    return current;
                }
            }
            current = current.getSubAbility();
        }
        return null;
    }
}
