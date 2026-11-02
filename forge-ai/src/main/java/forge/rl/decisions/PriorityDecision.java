package forge.rl.decisions;

import forge.rl.StateReader;
import forge.game.keyword.KeywordInterface;
import forge.game.Game;
import forge.card.mana.ManaCostShard;
import forge.game.GameStage;
import forge.game.GameEntity;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.cost.CostTap;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Map;

/** Ordinary priority: bind native variants before choosing, execute through Forge afterward. */
public final class PriorityDecision {
    public final Map<String, Object> data;
    final Player player;
    final List<Choice> bindings = new ArrayList<>();

    static void requireCreature(Card card) {
        if (!isVanillaCombatCreature(card)) {
            throw new UnsupportedOperationException("Vanilla combat creature");
        }
        requireSpell(card);
    }

    private static boolean isVanillaCombatCreature(Card card) {
        return card.isCreature() && card.getKeywords().isEmpty() && card.getStaticAbilities().isEmpty()
                && card.getTriggers().isEmpty();
    }

    static void requireSpell(Card card) {
        if (!hasOrdinaryCardAbilities(card)) {
            throw new UnsupportedOperationException("Ordinary priority creature");
        }
        // shortcut: passive Lyra traits only in priority/payment; prove other effects and combat separately.
        for (var keyword : card.getKeywords()) {
            if (hasUnsupportedCreatureKeyword(keyword)) {
                throw new UnsupportedOperationException("Ordinary priority creature keyword");
            }
        }
        for (var ability : card.getStaticAbilities()) {
            if (ability.isKeyword(Keyword.FLYING)) { continue; }
            var params = new HashMap<>(ability.getMapParams());
            params.remove("Description");
            if (!params.equals(Map.of("Mode", "Continuous", "Affected", "Angel.Other+YouCtrl",
                    "AddPower", "1", "AddToughness", "1", "AddKeyword", "Lifelink"))) {
                throw new UnsupportedOperationException("Ordinary priority creature static ability");
            }
        }
        SpellAbility sa = card.getFirstSpellAbility();
        boolean creature = card.isCreature() && sa.getApi() == ApiType.PermanentCreature && !sa.usesTargeting();
        boolean damage = card.isInstantOrSorcery() && sa.getApi() == ApiType.DealDamage && sa.usesTargeting()
                && sa.getMinTargets() == 1 && sa.getMaxTargets() == 1 && !sa.isDividedAsYouChoose()
                && !sa.hasParam("TargetingPlayer") && !sa.getTargetRestrictions().isRandomTarget()
                && sa.getTargetRestrictions().getZone().equals(List.of(ZoneType.Battlefield))
                && sa.getParamOrDefault("NumDmg", "").matches("[1-9][0-9]*");
        if (hasUnsupportedSpellShape(card, sa, creature, damage)) {
            throw new UnsupportedOperationException("Ordinary priority spell shape");
        }
        for (ManaCostShard shard : card.getManaCost()) {
            if (isUnsupportedManaShard(shard)) {
                throw new UnsupportedOperationException("Ordinary priority mana cost");
            }
        }
    }

    private static boolean hasOrdinaryCardAbilities(Card card) {
        return TriggerDecision.hasSupportedTriggers(card) && card.getReplacementEffects().isEmpty()
                && (card.isCreature() || card.getKeywords().isEmpty() && card.getStaticAbilities().isEmpty())
                && card.getSpellAbilities().size() == 1;
    }

    static void requireBasicLand(Card card) {
        if (isOutsideBasicLandScope(card)) {
            throw new UnsupportedOperationException("Ordinary priority basic land");
        }
        var mana = card.getSpellAbilities().stream().filter(sa -> !sa.isLandAbility()).toList();
        if (mana.size() != 1) { throw new UnsupportedOperationException("Ordinary basic-land variants"); }
        SpellAbility sa = mana.get(0);
        if (hasUnsupportedBasicManaShape(sa)) {
            throw new UnsupportedOperationException("Ordinary basic-land mana shape");
        }
    }

    private static void requireContext(Player player) {
        var game = player.getGame();
        if (hasNoLivePriority(game, player)) {
            throw new IllegalStateException("Player does not have live priority");
        }
        // shortcut: basic lands, passive Lyra traits, automatic ETB and fixed damage; extend with later families.
        if (hasPendingPriorityState(game)) {
            throw new UnsupportedOperationException("Ordinary priority pending context");
        }
        List<Card> visible = new ArrayList<>(game.getCardsIn(ZoneType.Battlefield));
        visible.addAll(player.getCardsIn(ZoneType.Hand));
        visible.addAll(game.getCardsIn(ZoneType.Stack));
        for (Card card : visible) {
            if (isHiddenCard(card, player)) {
                throw new UnsupportedOperationException("Ordinary priority visibility");
            }
            if (card.isBasicLand()) { requireBasicLand(card); }
            else { requireSpell(card); }
        }
        for (var entry : game.getStack()) {
            SpellAbility sa = entry.getSpellAbility();
            if (!isSupportedStackAbility(sa)) {
                throw new UnsupportedOperationException("Ordinary priority stack");
            }
        }
    }

    private static boolean isSupportedStackAbility(SpellAbility ability) {
        return TriggerDecision.isSupportedWrapper(ability)
                || ability.isSpell() && ability.getSubAbility() == null
                && (ability.getApi() == ApiType.PermanentCreature && !ability.usesTargeting()
                || ability.getApi() == ApiType.DealDamage && ability.usesTargeting());
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
                List<GameEntity> targets;
                sa.setActivatingPlayer(player);
                try {
                    playable = sa.canPlay();
                    targets = playable && sa.usesTargeting()
                            ? List.copyOf(sa.getTargetRestrictions().getAllCandidates(sa)) : null;
                }
                finally { sa.setActivatingPlayer(previous); }
                if (!playable) { continue; }
                String type = sa.isLandAbility() ? "play_land" : sa.isManaAbility() ? "activate_mana" : "cast_spell";
                int variant = card.getSpellAbilities().indexOf(sa);
                if (hasUnsupportedTargetDomain(targets)) {
                    throw new UnsupportedOperationException("Ordinary priority target domain");
                }
                bindings.add(new Choice(sa, card, type, variant, targets));
                Map<String, Object> item = new LinkedHashMap<>(Map.of("type", type, "name", card.getName(),
                        "zone", card.getZone().getZoneType().name(), "key", view.reference(card), "ability", variant));
                if (targets != null) {
                    item.put("targets", targets.stream().map(target -> Map.of("key", view.reference(target),
                            "name", target.getName(), "type", target instanceof Player ? "player" : "card")).toList());
                }
                items.add(Map.copyOf(item));
            }
        }
        data = Map.of("state", view.snapshot(), "options", Map.of("kind", "priority",
                "scope", "ordinary_lands_creatures_mana", "items", List.copyOf(items)));
    }

    Choice validate(List<Integer> answer) {
        if (hasInvalidAnswerHeader(answer)) {
            throw new IllegalArgumentException("Invalid priority answer");
        }
        if (answer.get(0) == 0) {
            if (answer.size() != 1) { throw new IllegalArgumentException("Invalid pass answer"); }
            return null;
        }
        Choice choice = bindings.get(answer.get(0) - 1);
        if (hasInvalidTargetAnswer(answer, choice)) {
            throw new IllegalArgumentException("Invalid priority target answer");
        }
        return choice;
    }

    public Choice bindChoice(List<Integer> answer) {
        Choice choice = validate(answer);
        if (choice != null) { choice.bindTarget(answer); }
        return choice;
    }

    private static List<SpellAbility> stack(Player player) {
        List<SpellAbility> result = new ArrayList<>();
        player.getGame().getStack().forEach(entry -> result.add(entry.getSpellAbility()));
        return result;
    }

    public static final class Choice {
        public final SpellAbility ability;
        final Card card;
        private final String type;
        private final int variant;
        final List<GameEntity> targets;
        int targetIndex = -1;
        private int landsPlayed;
        private int manaBefore;
        private List<SpellAbility> stackBefore;

        private Choice(SpellAbility ability, Card card, String type, int variant, List<GameEntity> targets) {
            this.ability = ability; this.card = card; this.type = type; this.variant = variant;
            this.targets = targets;
        }
        void bindTarget(List<Integer> answer) {
            if (targets != null) { targetIndex = answer.get(1); }
        }

        public boolean isCast() { return type.equals("cast_spell"); }
        public boolean isMana() { return type.equals("activate_mana"); }

        public void before(Player player, SpellAbility selected) {
            requireContext(player);
            if (isStaleOrUnboundAction(player, selected)) {
                throw new IllegalArgumentException("Stale or unbound priority action");
            }
            landsPlayed = player.getLandsPlayedThisTurn();
            manaBefore = player.getManaPool().totalMana();
            stackBefore = stack(player);
        }

        public void after(Player player, boolean reported) {
            if (hasChangedBoundTarget(player)) {
                throw new IllegalStateException("Native execution changed the bound final target");
            }
            Card current = player.getGame().getCardState(card, null);
            if (hasIncompleteLandExecution(player, reported, current)) {
                throw new IllegalStateException("Native land execution did not complete");
            }
            boolean produced = false;
            for (var mana : player.getManaPool()) {
                if (mana.getManaAbility() == ability.getManaPart() && mana.getSourceCard().getId() == card.getId()) { produced = true; }
            }
            if (hasIncompleteManaExecution(player, reported, current, produced)) {
                throw new IllegalStateException("Native basic-land activation did not complete");
            }
        }

        private boolean isStaleOrUnboundAction(Player player, SpellAbility selected) {
            return selected != ability || ability.getHostCard() != card
                    || player.getGame().getCardState(card, null) != card || !ability.canPlay()
                    || card.getSpellAbilities().get(variant) != ability;
        }

        private boolean hasChangedBoundTarget(Player player) {
            return targets != null && stack(player).stream().anyMatch(sa -> sa == ability)
                    && (targetIndex < 0 || ability.getTargets().size() != 1
                    || ability.getTargets().get(0) != targets.get(targetIndex));
        }

        private boolean hasIncompleteLandExecution(Player player, boolean reported, Card current) {
            return type.equals("play_land") && (!reported || current == null || !current.isInZone(ZoneType.Battlefield)
                    || current.getController() != player || player.getLandsPlayedThisTurn() != landsPlayed + 1);
        }

        private boolean hasIncompleteManaExecution(Player player, boolean reported, Card current, boolean produced) {
            return isMana() && (!reported || current != card || !card.isTapped()
                    || player.getManaPool().totalMana() != manaBefore + 1
                    || !stack(player).equals(stackBefore)
                    || !produced);
        }

        public Map<String, Object> result(Player player, StateReader view, boolean reported) {
            boolean onStack = stack(player).stream().anyMatch(sa -> sa == ability);
            return Map.of("state", view.snapshot(), "options", Map.of("kind", "cast_result",
                    "source", view.reference(ability.getHostCard()), "ability", variant,
                    "reported", reported, "onStack", onStack));
        }
    }

    private static boolean hasUnsupportedCreatureKeyword(KeywordInterface keyword) {
        return !(keyword.getKeyword() == Keyword.FLYING || keyword.getKeyword() == Keyword.FIRST_STRIKE
                || keyword.getKeyword() == Keyword.LIFELINK);
    }

    private static boolean hasUnsupportedSpellShape(Card card, SpellAbility sa, boolean creature, boolean damage) {
        return !sa.isSpell() || !(creature || damage) || sa.getSubAbility() != null
                || hasUnsupportedFixedCost(card, sa)
                || hasAlternateCasting(sa)
                || hasUnsupportedAnnouncements(sa);
    }

    private static boolean hasUnsupportedFixedCost(Card card, SpellAbility sa) {
        return sa.getPayCosts() == null || sa.getPayCosts().getCostParts().size() != 1
                || sa.getPayCosts().isMandatory() || !sa.getPayCosts().isOnlyManaCost()
                || !sa.getPayCosts().getTotalMana().equals(card.getManaCost())
                || card.getManaCost().isNoCost() || card.getManaCost().countX() != 0;
    }

    private static boolean hasAlternateCasting(SpellAbility sa) {
        return sa.isCopied() || sa.isOffering() || sa.isEmerge() || sa.getMayPlayOption() != null;
    }

    private static boolean hasUnsupportedAnnouncements(SpellAbility sa) {
        return sa.hasParam("Announce") || sa.hasParam("AnnounceType") || sa.hasParam("ManaConversion");
    }

    private static boolean isUnsupportedManaShard(ManaCostShard shard) {
        return !(shard == ManaCostShard.WHITE || shard == ManaCostShard.BLUE || shard == ManaCostShard.BLACK
                || shard == ManaCostShard.RED || shard == ManaCostShard.GREEN);
    }

    private static boolean isOutsideBasicLandScope(Card card) {
        return !card.isBasicLand() || card.isSnow() || !card.getKeywords().isEmpty() || !card.getTriggers().isEmpty()
                || !card.getStaticAbilities().isEmpty() || !card.getReplacementEffects().isEmpty();
    }

    private static boolean hasUnsupportedBasicManaShape(SpellAbility sa) {
        return sa.getApi() != ApiType.Mana || !sa.isActivatedAbility() || !sa.isManaAbility()
                || sa.getSubAbility() != null || sa.getManaPart() == null
                || !sa.getParamOrDefault("Produced", "").matches("[WUBRG]")
                || !sa.getParamOrDefault("Amount", "1").equals("1")
                || sa.getPayCosts() == null || sa.getPayCosts().getCostParts().size() != 1
                || !(sa.getPayCosts().getCostParts().get(0) instanceof CostTap)
                || !sa.getManaPart().getManaRestrictions().isEmpty()
                || !sa.getManaPart().getExtraManaRestriction().isEmpty();
    }

    private static boolean hasNoLivePriority(Game game, Player player) {
        return game.getAge() != GameStage.Play || game.isGameOver() || game.getPhaseHandler().getPriorityPlayer() != player;
    }

    private static boolean hasPendingPriorityState(Game game) {
        return !game.getCardsIn(ZoneType.Command).isEmpty() || !game.getTriggerHandler().getDelayedTriggers().isEmpty();
    }

    private static boolean isHiddenCard(Card card, Player player) {
        return card.isFaceDown() || !card.getView().canBeShownTo(player.getView());
    }

    private static boolean hasUnsupportedTargetDomain(List<GameEntity> targets) {
        return targets != null && targets.stream().anyMatch(target -> !(target instanceof Player)
                && !(target instanceof Card c && c.isCreature()));
    }

    private boolean hasInvalidAnswerHeader(List<Integer> answer) {
        return answer == null || answer.isEmpty() || ((List<?>) answer).stream().anyMatch(value -> !(value instanceof Integer))
                || answer.get(0) < 0 || answer.get(0) > bindings.size();
    }

    private static boolean hasInvalidTargetAnswer(List<Integer> answer, Choice choice) {
        return answer.size() != (choice.targets == null ? 1 : 2)
                || choice.targets != null && (answer.get(1) < -1 || answer.get(1) >= choice.targets.size());
    }
}
