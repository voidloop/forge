package forge.rl.requests;

import forge.rl.StateReader;
import forge.game.phase.PhaseHandler;
import forge.game.Game;

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
public final class ManaRequest implements DecisionRequest {
    public final Map<String, Object> data;
    final Choice choice;

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
                .anyMatch(card -> card.getSpellAbilities().stream().anyMatch(ManaRequest::supported));
    }

    private static SpellAbility requireContext(Player player) {
        var game = player.getGame();
        if (hasNoLiveManaPriority(game, player)) {
            throw new IllegalStateException("Player does not have live mana priority");
        }
        if (hasUnsupportedPriorityState(game, player)) {
            throw new UnsupportedOperationException("single mana priority context");
        }
        SpellAbility source = null;
        // shortcut: only this mana source and passive mana-preserving enchantments; expand after a native proof.
        for (Card card : game.getCardsIn(ZoneType.Battlefield)) {
            if (hasUnsupportedBattlefieldCard(card, player)) {
                throw new UnsupportedOperationException("single mana battlefield");
            }
            if (isManaPreservingEnchantment(card)) { continue; }
            if (isForeignOrNonLandSource(card, player)) {
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

    public ManaRequest(Player player) {
        SpellAbility ability = requireContext(player);
        Player activator = ability.getActivatingPlayer();
        boolean playable;
        ability.setActivatingPlayer(player);
        try { playable = ability.canPlay(); }
        finally { ability.setActivatingPlayer(activator); }
        choice = playable ? new Choice(ability, ability.getHostCard()) : null;
        try (var view = new StateReader(player, 0L)) {
            List<Map<String, Object>> items = new ArrayList<>();
            items.add(Map.of("type", "pass"));
            if (choice != null) {
                items.add(Map.of("type", "activate_mana", "name", choice.card.getName(),
                        "key", view.reference(choice.card)));
            }
            data = Map.of("state", view.snapshot(), "question", Map.of("kind", "priority_mana",
                    "scope", "pass_or_single_tap_mana", "items", List.copyOf(items)));
        }
    }

    public Choice selected() { return choice; }

    static int index(List<Integer> answer, int count) {
        if (hasInvalidIndexAnswer(answer, count)) {
            throw new IllegalArgumentException("Invalid mana decision answer");
        }
        return answer.get(0);
    }

    @Override public List<Integer> encodeAnswer(Object answer) {
        int count = choice == null ? 1 : 2;
        if (hasInvalidIndexAnswer((List<Integer>) answer, count)) {
            throw new IllegalArgumentException("Invalid mana priority answer");
        }
        return (List<Integer>) answer;
    }

    public record ColorRequest(Choice choice, Map<String, Object> data) implements DecisionRequest {
        @Override public List<Integer> encodeAnswer(Object answer) {
            byte chosen = (Byte) answer;
            var options = choice.colors().stream().toList();
            for (int i = 0; i < options.size(); i++) {
                if (options.get(i).getColorMask() == chosen) {
                    choice.color = chosen;
                    return List.of(i);
                }
            }
            throw new IllegalArgumentException("Native mana color outside request options");
        }
    }

    public static final class Choice {
        public final SpellAbility ability;
        final Card card;
        private int[] before;
        private int beforeTotal;
        byte color;
        private Player priority;
        private forge.game.phase.PhaseType phase;
        private int turn;

        private Choice(SpellAbility ability, Card card) { this.ability = ability; this.card = card; }

        public void before(Player player, SpellAbility selected) {
            SpellAbility current = requireContext(player);
            if (isStaleOrUnboundActivation(player, selected, current)) {
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

        ColorSet colors() { return ColorSet.fromNames(ability.getParam("Produced").substring(6).split(" ")); }

        public ColorRequest colorRequest(Player player, String message, SpellAbility source, ColorSet offered) {
            if (hasUnboundColorRequest(player, source)) {
                throw new IllegalStateException("Unbound or repeated mana color question");
            }
            samePriority(player);
            if (offered.getColor() != colors().getColor()) { throw new UnsupportedOperationException("AI-restricted mana menu must not be exported"); }
            try (var view = new StateReader(player, 0L)) {
                return new ColorRequest(this, Map.of("state", view.snapshot(), "question", Map.of("kind", "mana_color", "source", view.reference(card),
                        "prompt", message, "items", colors().stream().map(MagicColor.Color::getShortName).toList())));
            }
        }

        private void samePriority(Player player) {
            var handler = player.getGame().getPhaseHandler();
            if (hasChangedPriority(player, handler)) {
                throw new IllegalStateException("Mana activation changed priority or stack context");
            }
        }

        public void after(Player player, boolean reported) {
            samePriority(player);
            int[] after = amounts(player);
            if (hasIncompleteActivation(player, reported)) {
                throw new IllegalStateException("Native mana activation did not complete");
            }
            for (int i = 0; i < after.length; i++) {
                int expected = before[i] + (MagicColor.WUBRGC[i] == color ? 1 : 0);
                if (after[i] != expected) { throw new IllegalStateException("Native mana amount/color mismatch"); }
            }
        }

        private boolean isStaleOrUnboundActivation(Player player, SpellAbility selected, SpellAbility current) {
            return current != ability || selected != ability || ability.getHostCard() != card
                    || player.getGame().getCardState(card, null) != card || !ability.canPlay();
        }

        private boolean hasUnboundColorRequest(Player player, SpellAbility source) {
            return before == null || color != 0 || source != ability || ability.getHostCard() != card || !card.isTapped()
                    || player.getManaPool().totalMana() != beforeTotal || !java.util.Arrays.equals(amounts(player), before);
        }

        private boolean hasChangedPriority(Player player, PhaseHandler handler) {
            return handler.getPriorityPlayer() != priority || handler.getPhase() != phase || handler.getTurn() != turn
                    || !player.getGame().getStack().isEmpty();
        }

        private boolean hasIncompleteActivation(Player player, boolean reported) {
            return !reported || color == 0 || !card.isTapped() || !card.isInZone(ZoneType.Battlefield)
                    || player.getManaPool().totalMana() != beforeTotal + 1;
        }

        private static int[] amounts(Player player) {
            int[] result = new int[MagicColor.WUBRGC.length];
            for (int i = 0; i < result.length; i++) { result[i] = player.getManaPool().getAmountOfColor(MagicColor.WUBRGC[i]); }
            return result;
        }
    }

    private static boolean hasNoLiveManaPriority(Game game, Player player) {
        return game.getAge() != GameStage.Play || game.isGameOver() || game.getPhaseHandler().getPriorityPlayer() != player;
    }

    private static boolean hasUnsupportedPriorityState(Game game, Player player) {
        return !game.getStack().isEmpty() || !game.getCardsIn(ZoneType.Command).isEmpty()
                || !player.getCardsIn(ZoneType.Hand).isEmpty();
    }

    private static boolean hasUnsupportedBattlefieldCard(Card card, Player player) {
        return card.isFaceDown() || !card.getView().canBeShownTo(player.getView()) || !card.getTriggers().isEmpty();
    }

    private static boolean isManaPreservingEnchantment(Card card) {
        return card.isEnchantment() && card.getStaticAbilities().size() == 1
                && card.getStaticAbilities().get(0).getParamOrDefault("Mode", "").equals("UnspentMana")
                && card.getSpellAbilities().stream().noneMatch(SpellAbility::isActivatedAbility);
    }

    private static boolean isForeignOrNonLandSource(Card card, Player player) {
        return card.getController() != player || !card.isLand();
    }

    private static boolean hasInvalidIndexAnswer(List<Integer> answer, int count) {
        return answer == null || answer.size() != 1 || !(((List<?>) answer).get(0) instanceof Integer)
                || answer.get(0) < 0 || answer.get(0) >= count;
    }

    @Override public Map<String, Object> data() { return data; }
}
