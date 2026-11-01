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
import java.util.List;
import java.util.Map;

/** One mandatory self ETB draw: bind native preparation, leave the automatic draw in Forge. */
public final class TriggerDecision {
    public final Map<String, Object> data;
    private final Player player;
    private final WrappedAbility wrapper;
    private final List<SpellAbility> stackBefore;

    static boolean hasSupportedTriggers(Card card) {
        return card.getTriggers().isEmpty() || hasSelfEtbDraw(card);
    }

    private static boolean hasSelfEtbDraw(Card card) {
        if (!hasSinglePlainTrigger(card)) { return false; }
        Trigger trigger = card.getTriggers().get(0);
        return isSelfEtb(trigger) && isAutomaticDraw(trigger.copy(card, true).ensureAbility());
    }

    private static boolean hasSinglePlainTrigger(Card card) {
        return hasPlainCreatureTraits(card) && card.getTriggers().size() == 1;
    }

    private static boolean hasPlainCreatureTraits(Card card) {
        return card.isCreature() && card.getKeywords().isEmpty() && card.getStaticAbilities().isEmpty()
                && card.getReplacementEffects().isEmpty();
    }

    private static boolean isSelfEtb(Trigger trigger) {
        var params = new HashMap<>(trigger.getMapParams());
        params.remove("TriggerDescription");
        String execute = params.remove("Execute");
        return execute != null && params.equals(Map.of("Mode", "ChangesZone", "Origin", "Any",
                "Destination", "Battlefield", "ValidCard", "Card.Self"));
    }

    private static boolean isAutomaticDraw(SpellAbility ability) {
        return ability != null && ability.getApi() == ApiType.Draw
                && ability.getMapParams().equals(Map.of("DB", "Draw"))
                && !ability.usesTargeting() && ability.getTargets().isEmpty()
                && ability.getSubAbility() == null && ability.getParent() == null
                && ability.getPayCosts() != null && ability.getPayCosts().isOnlyManaCost()
                && ability.getPayCosts().getTotalMana().isZero() && !ability.isCopied();
    }

    static boolean isSupportedWrapper(SpellAbility ability) {
        return ability instanceof WrappedAbility wrapper && wrapper.isMandatory()
                && wrapper.getDecider() == null && hasSelfEtbDraw(wrapper.getHostCard())
                && isSelfEtb(wrapper.getTrigger()) && isAutomaticDraw(wrapper.getWrappedAbility());
    }

    private static List<SpellAbility> stack(Player player) {
        List<SpellAbility> result = new ArrayList<>();
        player.getGame().getStack().forEach(entry -> result.add(entry.getSpellAbility()));
        return List.copyOf(result);
    }

    public TriggerDecision(Player player, StateReader view, List<SpellAbility> abilities) {
        this.player = player;
        // shortcut: one mandatory self ETB draw; qualify simultaneous triggers and APNAP separately.
        if (!isSingleSupportedTrigger(abilities)) {
            throw new UnsupportedOperationException("Single mandatory self ETB draw preparation");
        }
        wrapper = (WrappedAbility) abilities.get(0);
        requireLiveSource();
        stackBefore = stack(player);
        if (stackBefore.contains(wrapper)) { throw new IllegalArgumentException("Trigger already on stack"); }
        data = Map.of("state", view.snapshot(), "options", Map.of("kind", "trigger_prepare",
                "scope", "single_self_etb_draw", "mandatory", true,
                "items", List.of(Map.of("key", view.reference(wrapper.getHostCard()), "trigger", 0))));
    }

    private static boolean isSingleSupportedTrigger(List<SpellAbility> abilities) {
        return abilities.size() == 1 && isSupportedWrapper(abilities.get(0));
    }

    private void requireLiveSource() {
        if (!hasLiveVisibleSource()) { throw new IllegalArgumentException("Stale or foreign ETB draw trigger"); }
    }

    private boolean hasLiveVisibleSource() {
        var game = player.getGame();
        Card host = wrapper.getHostCard();
        return game.getAge() == GameStage.Play && !game.isGameOver() && host.getGame() == game
                && wrapper.getActivatingPlayer() == player && host.getController() == player
                && game.getCardState(host, null) == host && host.isInZone(ZoneType.Battlefield)
                && !host.isFaceDown() && host.getView().canBeShownTo(player.getView())
                && game.getCardsIn(ZoneType.Command).isEmpty() && game.getTriggerHandler().getDelayedTriggers().isEmpty();
    }

    public void requireBoundOrder(List<SpellAbility> abilities) {
        if (!isExactOrder(abilities)) {
            throw new IllegalArgumentException("Unbound ETB draw order");
        }
        requireLiveSource();
    }

    private boolean isExactOrder(List<SpellAbility> abilities) {
        return abilities.size() == 1 && abilities.get(0) == wrapper;
    }

    public boolean isSource(SpellAbility ability) { return ability == wrapper; }

    public void apply(List<Integer> answer) {
        if (!List.of(0).equals(answer)) { throw new IllegalArgumentException("Mandatory ETB draw preparation answer"); }
        requireLiveSource();
        if (!isUnchangedPreparation()) {
            throw new IllegalArgumentException("Stale ETB draw preparation");
        }
        player.getGame().getStack().add(wrapper);
    }

    private boolean isUnchangedPreparation() {
        return isSupportedWrapper(wrapper) && stack(player).equals(stackBefore);
    }

    public List<Integer> encode() {
        requireLiveSource();
        List<SpellAbility> expected = new ArrayList<>();
        expected.add(wrapper);
        expected.addAll(stackBefore);
        if (!hasExpectedStack(expected)) {
            throw new IllegalStateException("Native ETB draw preparation did not preserve exact stack bindings");
        }
        return List.of(0);
    }

    private boolean hasExpectedStack(List<SpellAbility> expected) {
        return isSupportedWrapper(wrapper) && stack(player).equals(expected);
    }

    public static void requireResolution(Player player, SpellAbility ability, boolean setupTargets) {
        var stack = player.getGame().getStack();
        if (!isLiveResolution(player, setupTargets)) {
            throw new UnsupportedOperationException("Unbound ETB draw resolution");
        }
        SpellAbility top = stack.peekAbility();
        if (!isBoundResolution(player, ability, top)) {
            throw new IllegalArgumentException("Unbound ETB draw ability");
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
