package forge.rl;

import forge.game.card.Card;
import forge.game.card.CardState;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.ability.ApiType;
import forge.util.Lang;

import java.util.*;
import java.util.regex.Pattern;

/** Native printed-text rendering; detached action rendering never mutates a live choice. */
final class PrintedText {
    private PrintedText() { }

    static String clean(String text) {
        if (text == null) { return null; }
        return text.replace("\\n", "\n").replaceAll("\\s+", " ").trim();
    }

    static Integer stat(String value) {
        if (value == null) { return null; }
        try { return Integer.valueOf(value.trim()); }
        catch (NumberFormatException ignored) { return null; }
    }

    static String oracle(CardState state) {
        return state == null ? null : canonical(state.getOracleText(), state);
    }

    static String ability(SpellAbility ability) {
        if (ability.getTrigger() != null) { return trigger(ability.getTrigger()); }
        return canonical(ability.toUnsuppressedString(), ability.getCardState());
    }

    static String action(SpellAbility ability) {
        if (ability.getApi() == ApiType.Charm) { return null; }
        SpellAbility copy = ability.copy();
        if (copy.isSpell() && !copy.isBasicSpell()) {
            String description = copy.getDescription();
            int cost = description.lastIndexOf(" (Additional cost: ");
            if (cost >= 0) { copy.setDescription(description.substring(0, cost)); }
        }
        copy.setSubAbility(null);
        return ability(copy);
    }

    static String trigger(Trigger trigger) {
        String text = trigger.toString();
        SpellAbility ability = trigger.ensureAbility();
        if (ability != null) { text = trigger.replaceAbilityText(text, ability); }
        return canonical(text, trigger.getHostCard().getCurrentState());
    }

    static String effect(Card card) {
        List<String> parts = new ArrayList<>();
        card.getStaticAbilities().forEach(trait -> parts.add(clean(trait.toString())));
        card.getTriggers().forEach(trait -> parts.add(trigger(trait)));
        card.getReplacementEffects().forEach(trait -> parts.add(clean(trait.getDescription())));
        parts.removeIf(text -> text == null || text.isEmpty());
        return parts.isEmpty() ? oracle(card.getCurrentState()) : String.join(" ", parts);
    }

    static String copiedName(Card card) { return cloneName(card, Integer.MAX_VALUE, new HashSet<>()); }

    private static String cloneName(Card card, int depth, Set<Integer> seen) {
        List<forge.game.card.CardCloneStates> states = new ArrayList<>(card.getCloneStates().values());
        int size = Math.min(states.size(), depth);
        if (size == 0) { return null; }
        Card origin = states.get(size - 1).getOrigin();
        if (origin == card) { return cloneName(card, size - 1, seen); }
        if (origin == null || !seen.add(origin.getId())) { return null; }
        String name = cloneName(origin, Integer.MAX_VALUE, seen);
        return name == null ? origin.getName() : name;
    }

    private static String canonical(String text, CardState state) {
        text = clean(text);
        if (text == null || state == null || state.getFlavorName() == null || state.getFlavorName().isEmpty()) { return text; }
        String flavor = state.getFlavorName();
        String copied = copiedName(state.getCard());
        String name = copied == null ? state.getName() : copied;
        Map<String, String> names = new LinkedHashMap<>();
        names.put(flavor, name);
        String nickname = Lang.getInstance().getNickName(flavor);
        String printedNickname = Lang.getInstance().getNickName(name);
        if (!nickname.equals(flavor)) { names.put(nickname, printedNickname); }
        var pattern = Pattern.compile("(?<!\\w)(?:" + names.keySet().stream()
                .map(Pattern::quote).collect(java.util.stream.Collectors.joining("|")) + ")(?!\\w)");
        return pattern.matcher(text).replaceAll(match -> java.util.regex.Matcher.quoteReplacement(names.get(match.group())));
    }
}
