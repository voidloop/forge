package forge.rl.decisions;

import forge.rl.StateReader;
import forge.card.mana.ManaCostShard;
import forge.game.GameStage;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.cost.CostTap;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Ordinary priority: bind native variants before choosing, execute through Forge afterward. */
public final class PriorityDecision {
    public final Map<String, Object> data;
    private final Player player;
    private final List<Choice> bindings = new ArrayList<>();

    static void requireCreature(Card card) {
        if (!card.isCreature() || !card.getKeywords().isEmpty() || !card.getTriggers().isEmpty()
                || !card.getStaticAbilities().isEmpty() || !card.getReplacementEffects().isEmpty()
                || card.getSpellAbilities().size() != 1) {
            throw new UnsupportedOperationException("Ordinary priority creature");
        }
        SpellAbility sa = card.getFirstSpellAbility();
        if (!sa.isSpell() || sa.getApi() != ApiType.PermanentCreature || sa.getSubAbility() != null
                || sa.usesTargeting() || sa.getPayCosts() == null || sa.getPayCosts().getCostParts().size() != 1
                || sa.getPayCosts().isMandatory() || !sa.getPayCosts().isOnlyManaCost()
                || !sa.getPayCosts().getTotalMana().equals(card.getManaCost())
                || card.getManaCost().isNoCost() || card.getManaCost().countX() != 0
                || sa.isCopied() || sa.isOffering() || sa.isEmerge() || sa.getMayPlayOption() != null
                || sa.hasParam("Announce") || sa.hasParam("AnnounceType") || sa.hasParam("ManaConversion")) {
            throw new UnsupportedOperationException("Ordinary priority spell shape");
        }
        for (ManaCostShard shard : card.getManaCost()) {
            if (!(shard == ManaCostShard.WHITE || shard == ManaCostShard.BLUE || shard == ManaCostShard.BLACK
                    || shard == ManaCostShard.RED || shard == ManaCostShard.GREEN)) {
                throw new UnsupportedOperationException("Ordinary priority mana cost");
            }
        }
    }

    static void requireBasicLand(Card card) {
        if (!card.isBasicLand() || card.isSnow() || !card.getKeywords().isEmpty() || !card.getTriggers().isEmpty()
                || !card.getStaticAbilities().isEmpty() || !card.getReplacementEffects().isEmpty()) {
            throw new UnsupportedOperationException("Ordinary priority basic land");
        }
        var mana = card.getSpellAbilities().stream().filter(sa -> !sa.isLandAbility()).toList();
        if (mana.size() != 1) { throw new UnsupportedOperationException("Ordinary basic-land variants"); }
        SpellAbility sa = mana.get(0);
        if (sa.getApi() != ApiType.Mana || !sa.isActivatedAbility() || !sa.isManaAbility()
                || sa.getSubAbility() != null || sa.getManaPart() == null
                || !sa.getParamOrDefault("Produced", "").matches("[WUBRG]")
                || !sa.getParamOrDefault("Amount", "1").equals("1")
                || sa.getPayCosts() == null || sa.getPayCosts().getCostParts().size() != 1
                || !(sa.getPayCosts().getCostParts().get(0) instanceof CostTap)
                || !sa.getManaPart().getManaRestrictions().isEmpty()
                || !sa.getManaPart().getExtraManaRestriction().isEmpty()) {
            throw new UnsupportedOperationException("Ordinary basic-land mana shape");
        }
    }

    private static void requireContext(Player player) {
        var game = player.getGame();
        if (game.getAge() != GameStage.Play || game.isGameOver() || game.getPhaseHandler().getPriorityPlayer() != player) {
            throw new IllegalStateException("Player does not have live priority");
        }
        // shortcut: ordinary basic lands and fixed-cost vanilla creatures; extend with later families.
        if (!game.getCardsIn(ZoneType.Command).isEmpty() || !game.getTriggerHandler().getDelayedTriggers().isEmpty()) {
            throw new UnsupportedOperationException("Ordinary priority pending context");
        }
        List<Card> visible = new ArrayList<>(game.getCardsIn(ZoneType.Battlefield));
        visible.addAll(player.getCardsIn(ZoneType.Hand));
        visible.addAll(game.getCardsIn(ZoneType.Stack));
        for (Card card : visible) {
            if (card.isFaceDown() || !card.getView().canBeShownTo(player.getView())) {
                throw new UnsupportedOperationException("Ordinary priority visibility");
            }
            if (card.isBasicLand()) { requireBasicLand(card); }
            else { requireCreature(card); }
        }
        for (var entry : game.getStack()) {
            SpellAbility sa = entry.getSpellAbility();
            if (!sa.isSpell() || sa.getApi() != ApiType.PermanentCreature || sa.getSubAbility() != null || sa.usesTargeting()) {
                throw new UnsupportedOperationException("Ordinary priority stack");
            }
        }
    }

    public PriorityDecision(Player player, StateReader view) {
        this.player = player;
        requireContext(player);
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(Map.of("type", "pass"));
        List<Card> cards = new ArrayList<>(player.getCardsIn(ZoneType.Hand));
        cards.addAll(player.getCardsIn(ZoneType.Battlefield));
        for (Card card : cards) {
            for (SpellAbility sa : card.getSpellAbilities()) {
                Player previous = sa.getActivatingPlayer();
                boolean playable;
                sa.setActivatingPlayer(player);
                try { playable = sa.canPlay(); }
                finally { sa.setActivatingPlayer(previous); }
                if (!playable) { continue; }
                String type = sa.isLandAbility() ? "play_land" : sa.isManaAbility() ? "activate_mana" : "cast_spell";
                int variant = card.getSpellAbilities().indexOf(sa);
                bindings.add(new Choice(sa, card, type, variant));
                items.add(Map.of("type", type, "name", card.getName(), "zone", card.getZone().getZoneType().name(),
                        "key", view.reference(card), "ability", variant));
            }
        }
        data = Map.of("state", view.snapshot(), "options", Map.of("kind", "priority",
                "scope", "ordinary_lands_creatures_mana", "items", List.copyOf(items)));
    }

    public Choice apply(List<Integer> answer) {
        if (answer == null || answer.size() != 1 || !(((List<?>) answer).get(0) instanceof Integer)
                || answer.get(0) < 0 || answer.get(0) > bindings.size()) {
            throw new IllegalArgumentException("Invalid priority answer");
        }
        if (answer.get(0) == 0) { return null; }
        Choice choice = bindings.get(answer.get(0) - 1);
        choice.ability.setActivatingPlayer(player);
        return choice;
    }

    public List<Integer> encode(List<SpellAbility> selected) {
        if (selected == null) { return List.of(0); }
        if (selected.size() == 1) {
            for (int i = 0; i < bindings.size(); i++) {
                Choice choice = bindings.get(i);
                if (selected.get(0) == choice.ability && selected.get(0).getHostCard() == choice.card) { return List.of(i + 1); }
            }
        }
        throw new IllegalArgumentException("Native priority response outside exact bindings");
    }

    private static List<SpellAbility> stack(Player player) {
        List<SpellAbility> result = new ArrayList<>();
        player.getGame().getStack().forEach(entry -> result.add(entry.getSpellAbility()));
        return result;
    }

    public static final class Choice {
        public final SpellAbility ability;
        private final Card card;
        private final String type;
        private final int variant;
        private int landsPlayed;
        private int manaBefore;
        private List<SpellAbility> stackBefore;

        private Choice(SpellAbility ability, Card card, String type, int variant) {
            this.ability = ability; this.card = card; this.type = type; this.variant = variant;
        }
        public boolean isCast() { return type.equals("cast_spell"); }
        public boolean isMana() { return type.equals("activate_mana"); }

        public void before(Player player, SpellAbility selected) {
            requireContext(player);
            if (selected != ability || ability.getHostCard() != card
                    || player.getGame().getCardState(card, null) != card || !ability.canPlay()
                    || card.getSpellAbilities().get(variant) != ability) {
                throw new IllegalArgumentException("Stale or unbound priority action");
            }
            landsPlayed = player.getLandsPlayedThisTurn();
            manaBefore = player.getManaPool().totalMana();
            stackBefore = stack(player);
        }

        public void after(Player player, boolean reported) {
            Card current = player.getGame().getCardState(card, null);
            if (type.equals("play_land") && (!reported || current == null || !current.isInZone(ZoneType.Battlefield)
                    || current.getController() != player || player.getLandsPlayedThisTurn() != landsPlayed + 1)) {
                throw new IllegalStateException("Native land execution did not complete");
            }
            boolean produced = false;
            for (var mana : player.getManaPool()) {
                if (mana.getManaAbility() == ability.getManaPart() && mana.getSourceCard().getId() == card.getId()) { produced = true; }
            }
            if (isMana() && (!reported || current != card || !card.isTapped()
                    || player.getManaPool().totalMana() != manaBefore + 1
                    || !stack(player).equals(stackBefore)
                    || !produced)) {
                throw new IllegalStateException("Native basic-land activation did not complete");
            }
        }

        public Map<String, Object> result(Player player, StateReader view, boolean reported) {
            boolean onStack = stack(player).stream().anyMatch(sa -> sa == ability);
            return Map.of("state", view.snapshot(), "options", Map.of("kind", "cast_result",
                    "source", view.reference(ability.getHostCard()), "ability", variant,
                    "reported", reported, "onStack", onStack));
        }
    }
}
