package forge.rl.decisions;

import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.combat.CombatUtil;
import forge.game.mana.Mana;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.*;

/** Captures original final choices for PlayerControllerTeacher; never applies client answers. */
public final class DecisionRecorder {
    private DecisionRecorder() { }

    public static <T extends GameEntity> List<Integer> encode(DecisionRequest<T> request, Iterable<T> selected) {
        Objects.requireNonNull(selected, "Missing native selection");
        List<Integer> indices = new ArrayList<>();
        for (T entity : selected) {
            int index = -1;
            for (int i = 0; i < request.bindings.size(); i++) {
                if (request.bindings.get(i) == entity) { index = i; break; }
            }
            if (index < 0) { throw new IllegalArgumentException("Native selection outside request bindings"); }
            indices.add(index);
        }
        request.validate(indices);
        return List.copyOf(indices);
    }

    public static List<Integer> encode(StartingPlayerDecision request, Player chosen) { return encode(request.selection, List.of(chosen)); }

    public static List<Integer> encode(AttackersDecision request) {
        request.requireContext();
        var answer = new ArrayList<Integer>();
        for (Card card : request.combat.getAttackers()) {
            GameEntity defender = request.combat.getDefenderByAttacker(card);
            int a = request.attackers.indexOf(card), d = request.defenders.indexOf(defender);
            if (a < 0 || request.attackers.get(a) != card || d < 0 || request.defenders.get(d) != defender) {
                throw new IllegalArgumentException("Final attack declaration outside exact bindings");
            }
            answer.add(a);
            answer.add(d);
        }
        request.validate(answer);
        if (!CombatUtil.validateAttackers(request.combat)) { throw new IllegalArgumentException("Native attack declaration invalid"); }
        return List.copyOf(answer);
    }

    public static List<Integer> encode(BlockersDecision request) {
        request.requireContext(); request.requireAttackers();
        var answer = new ArrayList<Integer>();
        for (int a = 0; a < request.attackers.size(); a++) {
            for (Card card : request.combat.getBlockers(request.attackers.get(a))) {
                int b = request.blockers.indexOf(card);
                if (b < 0 || request.blockers.get(b) != card) {
                    throw new IllegalArgumentException("Final block declaration outside exact bindings");
                }
                answer.add(b); answer.add(a);
            }
        }
        request.validate(answer); request.validateNative();
        return List.copyOf(answer);
    }

    public static List<Integer> encode(CombatDamageDecision request, Map<Card, Integer> chosen) {
        if (chosen == null) { throw new UnsupportedOperationException("Deferred combat damage outside vanilla scope"); }
        var answer = new ArrayList<Integer>();
        for (var entry : chosen.entrySet()) {
            int index = entry.getKey() == null ? -1 : request.targets.indexOf(entry.getKey());
            if (index < 0 || request.targets.get(index) != entry.getKey() || entry.getValue() == null) {
                throw new IllegalArgumentException("Final damage map outside exact bindings");
            }
        }
        for (int i = 0; i < request.targets.size(); i++) {
            if (chosen.containsKey(request.targets.get(i))) { answer.add(i); answer.add(chosen.get(request.targets.get(i))); }
        }
        request.validate(answer);
        return List.copyOf(answer);
    }

    public static List<Integer> encode(CombatGraveyardDecision request, CardCollectionView chosen) {
        request.requireContext();
        return encode(request.selection, chosen);
    }

    private static List<Integer> encode(PriorityDecision request, List<SpellAbility> selected) {
        if (selected == null) { return List.of(0); }
        if (selected.size() == 1) {
            for (int i = 0; i < request.bindings.size(); i++) {
                PriorityDecision.Choice choice = request.bindings.get(i);
                if (selected.get(0) == choice.ability && selected.get(0).getHostCard() == choice.card) {
                    if (choice.targets == null) { return List.of(i + 1); }
                    if (choice.ability.getTargets().size() == 1) {
                        for (int j = 0; j < choice.targets.size(); j++) {
                            if (choice.ability.getTargets().get(0) == choice.targets.get(j)) { return List.of(i + 1, j); }
                        }
                    }
                    throw new IllegalArgumentException("Native final target outside previous bindings");
                }
            }
        }
        throw new IllegalArgumentException("Native priority response outside exact bindings");
    }

    public record PriorityAnswer(List<Integer> answer, PriorityDecision.Choice choice) { }

    public static PriorityAnswer capture(PriorityDecision request, List<SpellAbility> selected) {
        List<Integer> answer = encode(request, selected);
        PriorityDecision.Choice choice = request.validate(answer);
        if (choice != null) { choice.bindTarget(answer); }
        return new PriorityAnswer(answer, choice);
    }

    public static List<Integer> encode(ManaDecision request, List<SpellAbility> selected) {
        if (selected == null) { return List.of(0); }
        if (request.choice == null || selected.size() != 1 || selected.get(0) != request.choice.ability
                || selected.get(0).getHostCard() != request.choice.card) {
            throw new IllegalArgumentException("Native mana decision outside request bindings");
        }
        return List.of(1);
    }

    public static List<Integer> encodeColor(ManaDecision.Choice request, byte chosen) {
        var options = request.colors().stream().toList();
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).getColorMask() == chosen) { request.color = chosen; return List.of(i); }
        }
        throw new IllegalArgumentException("Native mana color outside request options");
    }

    public static List<Integer> encode(TriggerDecision request) {
        var current = request.preparedStack();
        List<Integer> answer = new ArrayList<>();
        for (int i = request.bindings.size() - 1; i >= 0; i--) { answer.add(request.bindingIndex(current.get(i))); }
        return List.copyOf(answer);
    }

    public static List<Integer> encode(ManaPaymentDecision request, boolean paid) {
        List<Integer> answer = new ArrayList<>(List.of(paid ? 1 : 0, request.root.getPayingManaAbilities().size()));
        for (SpellAbility ability : request.root.getPayingManaAbilities()) {
            int index = request.sources.indexOf(ability);
            if (!request.isBoundSourceAbility(index, ability)) { throw new IllegalArgumentException("Unbound payment ability variant"); }
            answer.add(index);
        }
        for (Mana mana : request.root.getPayingMana()) { answer.add(request.source(mana)); }
        request.validate(answer);
        if (!request.hasExpectedPaymentResources(answer)) {
            throw new IllegalStateException("Unrepresentable native payment resources");
        }
        return List.copyOf(answer);
    }
}
