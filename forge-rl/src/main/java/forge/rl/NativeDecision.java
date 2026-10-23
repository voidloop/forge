package forge.rl;

import forge.game.player.Player;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.Predicate;

/** The current blocking native request; validating a reply never plays it. */
public final class NativeDecision {
    public record CombatAssignment(Card creature, GameEntity target, boolean remove) { }
    private final String kind;
    private final Player chooser;
    private final SpellAbility ability;
    private final String prompt;
    private final List<?> options;
    private final int minimum;
    private final int maximum;
    private final boolean allowEmpty;
    private final boolean repeat;
    private final boolean ordered;
    private final boolean number;
    private final Predicate<List<Integer>> validator;
    private final Map<String, Object> context;

    public NativeDecision(String kind, Player chooser, SpellAbility ability, String prompt,
            List<?> options, int minimum, int maximum, boolean allowEmpty,
            boolean repeat, boolean ordered, boolean number, Predicate<List<Integer>> validator) {
        this(kind, chooser, ability, prompt, options, minimum, maximum, allowEmpty,
                repeat, ordered, number, validator, Map.of());
    }

    public NativeDecision(String kind, Player chooser, SpellAbility ability, String prompt,
            List<?> options, int minimum, int maximum, boolean allowEmpty,
            boolean repeat, boolean ordered, boolean number, Predicate<List<Integer>> validator,
            Map<String, Object> context) {
        if (kind == null || chooser == null || minimum > maximum || minimum < 0 && !number) {
            throw new IllegalArgumentException("Invalid native request bounds");
        }
        this.kind = kind;
        this.chooser = chooser;
        this.ability = ability;
        this.prompt = prompt;
        this.options = Collections.unmodifiableList(new ArrayList<>(options));
        this.minimum = minimum;
        this.maximum = maximum;
        this.allowEmpty = allowEmpty;
        this.repeat = repeat;
        this.ordered = ordered;
        this.number = number;
        this.validator = validator;
        this.context = Collections.unmodifiableMap(new LinkedHashMap<>(context));
    }

    public String getKind() { return kind; }
    public Player getChooser() { return chooser; }
    public SpellAbility getAbility() { return ability; }
    public String getPrompt() { return prompt; }
    public List<?> getOptions() { return options; }
    public int getMinimum() { return minimum; }
    public int getMaximum() { return maximum; }
    public boolean allowsEmpty() { return allowEmpty; }
    public boolean allowsRepeat() { return repeat; }
    public boolean isOrdered() { return ordered; }
    public boolean isNumber() { return number; }
    public Map<String, Object> getContext() { return context; }

    public void validateAnswer(List<Integer> answer) {
        if (answer == null) { throw new IllegalArgumentException("Missing native answer"); }
        if (answer.isEmpty() && allowEmpty) { return; }
        if (number) {
            if (answer.size() != 1 || answer.get(0) == null
                    || answer.get(0) < minimum || answer.get(0) > maximum) {
                throw new IllegalArgumentException("Number is outside the native bounds");
            }
        } else {
            if (answer.size() < minimum || answer.size() > maximum
                    || !repeat && new HashSet<>(answer).size() != answer.size()) {
                throw new IllegalArgumentException("Invalid native answer cardinality");
            }
            for (Integer index : answer) {
                if (index == null || index < 0 || index >= options.size()) {
                    throw new IllegalArgumentException("Answer is outside the native options");
                }
            }
        }
        if (validator != null && !validator.test(answer)) {
            throw new IllegalArgumentException("Native validation rejected this complete answer");
        }
    }
}
