package forge.rl.decisions;

import forge.rl.StateReader;

import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.game.GameStage;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.cost.CostTap;
import forge.game.cost.CostPartMana;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** First mana family: a single visible land, tap cost, one mana and a literal color menu. */
public final class ManaDecision {
    public final Map<String, Object> data;
    private final Choice choice;

    public static boolean supported(SpellAbility ability) {
        return ability.getApi() == ApiType.Mana && ability.isActivatedAbility() && ability.isManaAbility()
                && ability.getPayCosts() != null && ability.getManaPart() != null && ability.getSubAbility() == null
                && ability.getParamOrDefault("Amount", "1").equals("1") && !ability.hasParam("Each")
                && !ability.hasParam("Chooser") && !ability.hasParam("Optional")
                && ability.getParamOrDefault("Defined", "You").equals("You")
                && ability.getParamOrDefault("Produced", "").matches("Combo [WUBRG]( [WUBRG])+")
                && ColorSet.fromNames(ability.getParam("Produced").substring(6).split(" ")).countColors() > 1
                && ability.getPayCosts().hasTapCost() && ability.getPayCosts().getCostParts().stream()
                    .allMatch(part -> part instanceof CostTap || part instanceof CostPartMana mana && mana.getMana().isZero());
    }

    public static boolean hasSource(Player player) {
        return player.getCardsIn(ZoneType.Battlefield).stream().filter(Card::isLand)
                .anyMatch(card -> card.getSpellAbilities().stream().anyMatch(ManaDecision::supported));
    }

    private static SpellAbility requireContext(Player player) {
        var game = player.getGame();
        if (game.getAge() != GameStage.Play || game.isGameOver() || game.getPhaseHandler().getPriorityPlayer() != player) {
            throw new IllegalStateException("Player does not have live mana priority");
        }
        if (!game.getStack().isEmpty() || !game.getCardsIn(ZoneType.Command).isEmpty()
                || !player.getCardsIn(ZoneType.Hand).isEmpty()) {
            throw new UnsupportedOperationException("single mana priority context");
        }
        SpellAbility source = null;
        // shortcut: only this mana source and passive mana-preserving enchantments; expand after a native proof.
        for (Card card : game.getCardsIn(ZoneType.Battlefield)) {
            if (card.isFaceDown() || !card.getView().canBeShownTo(player.getView()) || !card.getTriggers().isEmpty()) {
                throw new UnsupportedOperationException("single mana battlefield");
            }
            if (card.isEnchantment() && card.getStaticAbilities().size() == 1
                    && card.getStaticAbilities().get(0).getParamOrDefault("Mode", "").equals("UnspentMana")
                    && card.getSpellAbilities().stream().noneMatch(SpellAbility::isActivatedAbility)) { continue; }
            if (card.getController() != player || !card.isLand()) {
                throw new UnsupportedOperationException("single mana source");
            }
            for (SpellAbility ability : card.getSpellAbilities()) {
                if (ability.isLandAbility()) { continue; }
                if (!supported(ability) || source != null) { throw new UnsupportedOperationException("single mana ability shape"); }
                source = ability;
            }
        }
        if (source == null) { throw new UnsupportedOperationException("Missing mana source"); }
        return source;
    }

    public ManaDecision(Player player, StateReader view) {
        SpellAbility ability = requireContext(player);
        Player activator = ability.getActivatingPlayer();
        boolean playable;
        ability.setActivatingPlayer(player);
        try { playable = ability.canPlay(); }
        finally { ability.setActivatingPlayer(activator); }
        choice = playable ? new Choice(ability, ability.getHostCard()) : null;
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(Map.of("type", "pass"));
        if (choice != null) {
            items.add(Map.of("type", "activate_mana", "name", choice.card.getName(),
                    "key", view.reference(choice.card)));
        }
        data = Map.of("state", view.snapshot(), "options", Map.of("kind", "priority_mana",
                "scope", "pass_or_single_tap_mana", "items", List.copyOf(items)));
    }

    public Choice apply(List<Integer> answer, Player player) {
        int index = index(answer, choice == null ? 1 : 2);
        if (index == 0) { return null; }
        choice.ability.setActivatingPlayer(player);
        return choice;
    }

    public List<Integer> encode(List<SpellAbility> selected) {
        if (selected == null) { return List.of(0); }
        if (choice == null || selected.size() != 1 || selected.get(0) != choice.ability
                || selected.get(0).getHostCard() != choice.card) {
            throw new IllegalArgumentException("Native mana decision outside request bindings");
        }
        return List.of(1);
    }

    public Choice selected() { return choice; }

    private static int index(List<Integer> answer, int count) {
        if (answer == null || answer.size() != 1 || !(((List<?>) answer).get(0) instanceof Integer)
                || answer.get(0) < 0 || answer.get(0) >= count) {
            throw new IllegalArgumentException("Invalid mana decision answer");
        }
        return answer.get(0);
    }

    public static final class Choice {
        public final SpellAbility ability;
        final Card card;
        private int[] before;
        private int beforeTotal;
        private byte color;
        private Player priority;
        private forge.game.phase.PhaseType phase;
        private int turn;

        private Choice(SpellAbility ability, Card card) { this.ability = ability; this.card = card; }

        public void before(Player player, SpellAbility selected) {
            if (requireContext(player) != ability || selected != ability || ability.getHostCard() != card
                    || player.getGame().getCardState(card, null) != card || !ability.canPlay()) {
                throw new IllegalArgumentException("Stale or unbound mana activation");
            }
            if (!ability.getManaPart().getExpressChoice().isEmpty()) {
                throw new UnsupportedOperationException("Preplanned mana colors are outside the first activation scope");
            }
            before = amounts(player);
            beforeTotal = player.getManaPool().totalMana();
            priority = player.getGame().getPhaseHandler().getPriorityPlayer();
            phase = player.getGame().getPhaseHandler().getPhase();
            turn = player.getGame().getPhaseHandler().getTurn();
        }

        private ColorSet colors() { return ColorSet.fromNames(ability.getParam("Produced").substring(6).split(" ")); }

        public Map<String, Object> colorRequest(Player player, StateReader view, String message, SpellAbility source, ColorSet offered) {
            if (before == null || color != 0 || source != ability || ability.getHostCard() != card || !card.isTapped()
                    || player.getManaPool().totalMana() != beforeTotal || !java.util.Arrays.equals(amounts(player), before)) {
                throw new IllegalStateException("Unbound or repeated mana color question");
            }
            samePriority(player);
            if (offered.getColor() != colors().getColor()) { throw new UnsupportedOperationException("AI-restricted mana menu must not be exported"); }
            return Map.of("state", view.snapshot(), "options", Map.of("kind", "mana_color", "source", view.reference(card),
                    "prompt", message, "items", colors().stream().map(MagicColor.Color::getShortName).toList()));
        }

        public byte applyColor(List<Integer> answer) {
            var options = colors().stream().toList();
            color = options.get(index(answer, options.size())).getColorMask();
            return color;
        }

        public List<Integer> encodeColor(byte chosen) {
            var options = colors().stream().toList();
            for (int i = 0; i < options.size(); i++) {
                if (options.get(i).getColorMask() == chosen) { color = chosen; return List.of(i); }
            }
            throw new IllegalArgumentException("Native mana color outside request options");
        }

        private void samePriority(Player player) {
            var handler = player.getGame().getPhaseHandler();
            if (handler.getPriorityPlayer() != priority || handler.getPhase() != phase || handler.getTurn() != turn
                    || !player.getGame().getStack().isEmpty()) {
                throw new IllegalStateException("Mana activation changed priority or stack context");
            }
        }

        public void after(Player player, boolean reported) {
            samePriority(player);
            int[] after = amounts(player);
            if (!reported || color == 0 || !card.isTapped() || !card.isInZone(ZoneType.Battlefield)
                    || player.getManaPool().totalMana() != beforeTotal + 1) {
                throw new IllegalStateException("Native mana activation did not complete");
            }
            for (int i = 0; i < after.length; i++) {
                int expected = before[i] + (MagicColor.WUBRGC[i] == color ? 1 : 0);
                if (after[i] != expected) { throw new IllegalStateException("Native mana amount/color mismatch"); }
            }
        }

        private static int[] amounts(Player player) {
            int[] result = new int[MagicColor.WUBRGC.length];
            for (int i = 0; i < result.length; i++) { result[i] = player.getManaPool().getAmountOfColor(MagicColor.WUBRGC[i]); }
            return result;
        }
    }
}
