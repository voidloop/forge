package forge.rl.decisions;

import forge.game.GameStage;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;
import forge.rl.StateReader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Order exact mandatory ETB wrappers; automatic effects and resolution stay in Forge. */
public final class TriggerDecision {
    public final Map<String, Object> data;
    private final Player player;
    private final List<Binding> bindings;
    private final List<SpellAbility> stackBefore;

    private record Binding(WrappedAbility wrapper, Card host, Trigger trigger) { }

    static boolean hasSupportedTriggers(Card card) {
        return card.getTriggers().isEmpty() || hasAutomaticEtb(card);
    }

    private static boolean hasAutomaticEtb(Card card) {
        if (!hasSinglePlainTrigger(card)) { return false; }
        Trigger trigger = card.getTriggers().get(0);
        return isMandatoryEtb(trigger) && isAutomaticEffect(trigger.copy(card, true).ensureAbility());
    }

    private static boolean hasSinglePlainTrigger(Card card) {
        return hasPlainCreatureTraits(card) && card.getTriggers().size() == 1;
    }

    private static boolean hasPlainCreatureTraits(Card card) {
        return card.isCreature() && card.getKeywords().isEmpty() && card.getStaticAbilities().isEmpty()
                && card.getReplacementEffects().isEmpty();
    }

    private static boolean isMandatoryEtb(Trigger trigger) {
        var params = new HashMap<>(trigger.getMapParams());
        params.remove("TriggerDescription");
        String execute = params.remove("Execute");
        String valid = params.remove("ValidCard");
        String origin = params.remove("Origin");
        String zones = params.remove("TriggerZones");
        return execute != null && valid != null && !valid.isBlank()
                && (origin == null || origin.equals("Any")) && (zones == null || zones.equals("Battlefield"))
                && params.equals(Map.of("Mode", "ChangesZone", "Destination", "Battlefield"));
    }

    private static boolean isAutomaticEffect(SpellAbility ability) {
        return ability != null && hasAutomaticEffectParameters(ability)
                && !ability.usesTargeting() && ability.getTargets().isEmpty()
                && ability.getSubAbility() == null && ability.getParent() == null
                && ability.getPayCosts() != null && ability.getPayCosts().isOnlyManaCost()
                && ability.getPayCosts().getTotalMana().isZero() && !ability.isCopied();
    }

    private static boolean hasAutomaticEffectParameters(SpellAbility ability) {
        if (ability.getApi() != ApiType.Draw && ability.getApi() != ApiType.GainLife) { return false; }
        var params = new HashMap<>(ability.getMapParams());
        String api = params.remove("DB");
        String amount = params.remove(ability.getApi() == ApiType.Draw ? "NumCards" : "LifeAmount");
        String defined = params.remove("Defined");
        return ability.getApi().name().equals(api) && params.isEmpty()
                && (defined == null || defined.equals("You")) && hasFixedPositiveAmount(ability, amount);
    }

    private static boolean hasFixedPositiveAmount(SpellAbility ability, String amount) {
        return amount == null ? ability.getApi() == ApiType.Draw : amount.matches("[1-9][0-9]*");
    }

    static boolean isSupportedWrapper(SpellAbility ability) {
        return ability instanceof WrappedAbility wrapper && wrapper.isMandatory()
                && wrapper.getDecider() == null && hasAutomaticEtb(wrapper.getHostCard())
                && wrapper.getTrigger() == wrapper.getHostCard().getTriggers().get(0)
                && isMandatoryEtb(wrapper.getTrigger()) && isAutomaticEffect(wrapper.getWrappedAbility());
    }

    private static List<SpellAbility> stack(Player player) {
        List<SpellAbility> result = new ArrayList<>();
        player.getGame().getStack().forEach(entry -> result.add(entry.getSpellAbility()));
        return List.copyOf(result);
    }

    public TriggerDecision(Player player, StateReader view, List<SpellAbility> abilities) {
        this.player = player;
        // shortcut: one or two distinct ETB sources per controller; qualify repeated sources and larger batches separately.
        if (!isSupportedBatch(abilities)) {
            throw new UnsupportedOperationException("Mandatory automatic ETB preparation for one or two sources");
        }
        bindings = abilities.stream().map(ability -> {
            var wrapper = (WrappedAbility) ability;
            return new Binding(wrapper, wrapper.getHostCard(), wrapper.getTrigger());
        }).toList();
        requireLiveSources();
        stackBefore = stack(player);
        if (stackBefore.stream().anyMatch(this::isSource)) { throw new IllegalArgumentException("Trigger already on stack"); }
        data = Map.of("state", view.snapshot(), "options", Map.of("kind", "trigger_prepare",
                "scope", isLegacySingleDraw() ? "single_self_etb_draw" : "mandatory_etb", "mandatory", true,
                "items", bindings.stream().map(binding -> Map.of("key", view.reference(binding.host()), "trigger", 0)).toList()));
    }

    private boolean isLegacySingleDraw() {
        return bindings.size() == 1 && "Card.Self".equals(bindings.get(0).trigger().getParam("ValidCard"))
                && bindings.get(0).wrapper().getWrappedAbility().getMapParams().equals(Map.of("DB", "Draw"));
    }

    private static boolean isSupportedBatch(List<SpellAbility> abilities) {
        return !abilities.isEmpty() && abilities.size() <= 2 && abilities.stream().allMatch(TriggerDecision::isSupportedWrapper)
                && abilities.stream().map(SpellAbility::getHostCard).distinct().count() == abilities.size();
    }

    private void requireLiveSources() {
        if (!bindings.stream().allMatch(this::hasLiveVisibleSource)) {
            throw new IllegalArgumentException("Stale or foreign ETB trigger");
        }
    }

    private boolean hasLiveVisibleSource(Binding binding) {
        var game = player.getGame();
        Card host = binding.host();
        WrappedAbility wrapper = binding.wrapper();
        return game.getAge() == GameStage.Play && !game.isGameOver() && host.getGame() == game
                && wrapper.getHostCard() == host && wrapper.getTrigger() == binding.trigger() && isSupportedWrapper(wrapper)
                && wrapper.getActivatingPlayer() == player && host.getController() == player
                && game.getCardState(host, null) == host && host.isInZone(ZoneType.Battlefield)
                && !host.isFaceDown() && host.getView().canBeShownTo(player.getView())
                && game.getCardsIn(ZoneType.Command).isEmpty() && game.getTriggerHandler().getDelayedTriggers().isEmpty();
    }

    public void requireBoundOrder(List<SpellAbility> abilities) {
        if (!sameAbilities(abilities, bindings.stream().map(binding -> (SpellAbility) binding.wrapper()).toList())) {
            throw new IllegalArgumentException("Unbound ETB order");
        }
        requireLiveSources();
    }

    private static boolean sameAbilities(List<SpellAbility> first, List<SpellAbility> second) {
        if (first.size() != second.size()) { return false; }
        for (int i = 0; i < first.size(); i++) {
            if (first.get(i) != second.get(i)) { return false; }
        }
        return true;
    }

    public boolean isSource(SpellAbility ability) {
        return bindings.stream().anyMatch(binding -> binding.wrapper() == ability);
    }

    public void apply(List<Integer> answer) {
        if (!isPermutation(answer)) { throw new IllegalArgumentException("Mandatory ETB preparation answer"); }
        requireLiveSources();
        if (!sameAbilities(stack(player), stackBefore)) {
            throw new IllegalArgumentException("Stale ETB preparation");
        }
        for (int index : answer) { player.getGame().getStack().add(bindings.get(index).wrapper()); }
    }

    private boolean isPermutation(List<Integer> answer) {
        return answer != null && answer.size() == bindings.size()
                && ((List<?>) answer).stream().allMatch(value -> value instanceof Integer index && index >= 0 && index < bindings.size())
                && new HashSet<>(answer).size() == bindings.size();
    }

    public List<Integer> encode() {
        requireLiveSources();
        var current = stack(player);
        if (!hasPreservedStackTail(current)) {
            throw new IllegalStateException("Native ETB preparation did not preserve exact stack bindings");
        }
        List<Integer> answer = new ArrayList<>();
        for (int i = bindings.size() - 1; i >= 0; i--) { answer.add(bindingIndex(current.get(i))); }
        if (!isPermutation(answer)) {
            throw new IllegalStateException("Native ETB preparation did not preserve exact wrappers");
        }
        return List.copyOf(answer);
    }

    private boolean hasPreservedStackTail(List<SpellAbility> current) {
        return current.size() == bindings.size() + stackBefore.size()
                && sameAbilities(current.subList(bindings.size(), current.size()), stackBefore);
    }

    private int bindingIndex(SpellAbility ability) {
        for (int i = 0; i < bindings.size(); i++) {
            if (bindings.get(i).wrapper() == ability) { return i; }
        }
        return -1;
    }

    public static void requireResolution(Player player, SpellAbility ability, boolean setupTargets) {
        var stack = player.getGame().getStack();
        if (!isLiveResolution(player, setupTargets)) {
            throw new UnsupportedOperationException("Unbound ETB resolution");
        }
        SpellAbility top = stack.peekAbility();
        if (!isBoundResolution(player, ability, top)) {
            throw new IllegalArgumentException("Unbound ETB ability");
        }
    }

    private static boolean isLiveResolution(Player player, boolean setupTargets) {
        var stack = player.getGame().getStack();
        return !setupTargets && stack.isResolving() && !stack.isEmpty();
    }

    private static boolean isBoundResolution(Player player, SpellAbility ability, SpellAbility top) {
        return isSupportedWrapper(top) && ((WrappedAbility) top).getWrappedAbility() == ability
                && top.getActivatingPlayer() == player && ability.getActivatingPlayer() == player
                && ability.getHostCard() == top.getHostCard();
    }
}
