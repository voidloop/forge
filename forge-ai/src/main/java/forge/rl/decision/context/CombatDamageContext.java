package forge.rl.decision.context;

import forge.rl.StateReader;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import java.util.*;

/** Final native damage map; target bindings are captured before the choice. */
public final class CombatDamageContext implements DecisionContext {
    private static final String KIND = "combat_damage";

    public final Map<String, Object> data;
    private final Player player;
    private final Combat combat;
    private final Card source;
    private final GameEntity defender;
    final List<Card> targets;
    private final CardCollectionView offered;
    private final int damage;

    public CombatDamageContext(Player player, Card source, CardCollectionView targets, CardCollectionView remaining, int damage, GameEntity defender, boolean overrideOrder) {
        this(null, player, source, targets, remaining, damage, defender, overrideOrder);
    }

    public CombatDamageContext(StateReader reader, Player player, Card source, CardCollectionView targets, CardCollectionView remaining, int damage, GameEntity defender, boolean overrideOrder) {
        this.player = player;
        this.combat = Objects.requireNonNull(player.getGame().getCombat(), "No combat for damage assignment");
        this.source = Objects.requireNonNull(source);
        this.defender = defender;
        this.offered = Objects.requireNonNull(targets);
        this.targets = List.copyOf(targets);
        this.damage = damage;
        requireContext();
        if (hasInvalidAssignmentArguments(remaining, overrideOrder)) {
            throw new IllegalArgumentException("Invalid native damage assignment arguments");
        }
        try (var localReader = reader == null ? new StateReader(player, 0L) : null) {
            var view = reader == null ? localReader : reader;
            data = Map.of("state", view.snapshot(), "question", StateReader.data(
                    "kind", KIND, "scope", "ordinary_vanilla_duel", "source", view.reference(source),
                    "defender", defender == null ? null : view.reference(defender), "damage", damage,
                    "items", targets.stream().map(c -> Map.of("key", view.reference(c), "name", c.getName())).toList()));
        }
    }

    private static void requireSame(List<Card> bound, Iterable<Card> current) {
        var iterator = current.iterator();
        for (Card card : bound) {
            if (!iterator.hasNext() || iterator.next() != card) {
                throw new IllegalArgumentException("Stale or unbound damage targets");
            }
        }
        if (iterator.hasNext()) { throw new IllegalArgumentException("Damage targets changed"); }
    }

    private void requireContext() {
        var game = player.getGame();
        if (hasInvalidDamageContext()) {
            throw new IllegalArgumentException("Invalid or stale native combat damage context");
        }
        requireScope(player);
        if (hasInvalidTargets()) {
            throw new IllegalArgumentException("Empty or duplicate damage targets");
        }
        if (defender != null) {
            if (hasUnboundAttackingAssignment()) {
                throw new IllegalArgumentException("Unbound attacking damage assignment");
            }
            requireSame(targets, combat.getBlockers(source));
        } else {
            if (hasUnboundBlockingAssignment()) {
                throw new IllegalArgumentException("Unbound blocking damage assignment");
            }
            requireSame(targets, combat.getAttackersBlockedBy(source));
        }
        requireSame(targets, offered);
        for (Card card : targets) {
            if (isForeignOrStaleTarget(card)) {
                throw new IllegalArgumentException("Foreign or stale damage target");
            }
        }
    }

    static void requireScope(Player player) {
        var game = player.getGame();
        var combat = game.getCombat();
        if (hasInvalidDamagePhase(game, combat)) {
            throw new IllegalArgumentException("Invalid native combat damage phase");
        }
        // shortcut: current unordered vanilla duel only; legacy orders and keywords need their own proof.
        if (isOutsideDamageScope(game, player, combat)) {
            throw new UnsupportedOperationException("Ordinary combat damage scope");
        }
        for (Card card : game.getCardsIn(ZoneType.Battlefield)) {
            if (isHiddenCard(card, player)) {
                throw new UnsupportedOperationException("Combat damage visibility");
            }
            if (card.isBasicLand()) { PriorityContext.requireBasicLand(card); }
            else { PriorityContext.requireCreature(card); }
        }
    }

    void validate(List<Integer> answer) {
        requireContext();
        if (hasInvalidPairEncoding(answer)) {
            throw new IllegalArgumentException("Invalid combat damage pairs");
        }
        Map<Card, Integer> result = new LinkedHashMap<>();
        long total = 0;
        for (int i = 0; i < answer.size(); i += 2) {
            int target = answer.get(i), amount = answer.get(i + 1);
            addAssignment(result, target, amount);
            total += amount;
        }
        if (total != damage) { throw new IllegalArgumentException("Combat damage must be assigned in full"); }
    }

    private boolean hasInvalidAssignmentArguments(CardCollectionView remaining, boolean overrideOrder) {
        return !overrideOrder || (remaining == null) != (defender == null)
                || remaining != null && (remaining.stream().noneMatch(c -> c == source)
                        || remaining.stream().anyMatch(c -> !combat.isAttacking(c)
                                || player.getGame().getCardState(c, null) != c));
    }

    private boolean hasInvalidDamageContext() {
        var game = player.getGame();
        return game.getAge() != GameStage.Play || game.isGameOver() || game.getCombat() != combat
                || !game.getPhaseHandler().is(PhaseType.COMBAT_DAMAGE, combat.getAttackingPlayer())
                || source.getController() != player || game.getCardState(source, null) != source
                || !source.isInZone(ZoneType.Battlefield) || damage < 0 || source.getNetCombatDamage() != damage;
    }

    private boolean hasInvalidTargets() {
        return targets.isEmpty() || new HashSet<>(targets).size() != targets.size();
    }

    private boolean hasUnboundAttackingAssignment() {
        return player != combat.getAttackingPlayer() || !combat.isAttacking(source)
                || defender != player.getOpponents().get(0) || combat.getDefenderByAttacker(source) != defender;
    }

    private boolean hasUnboundBlockingAssignment() {
        return player == combat.getAttackingPlayer() || !combat.isBlocking(source) || targets.size() != 1;
    }

    private boolean isForeignOrStaleTarget(Card card) {
        var game = player.getGame();
        return game.getCardState(card, null) != card || !card.isInZone(ZoneType.Battlefield)
                || card.getController() != player.getOpponents().get(0);
    }

    private static boolean hasInvalidDamagePhase(Game game, Combat combat) {
        return game.getAge() != GameStage.Play || game.isGameOver() || combat == null
                || !game.getPhaseHandler().is(PhaseType.COMBAT_DAMAGE, combat.getAttackingPlayer());
    }

    private static boolean isOutsideDamageScope(Game game, Player player, Combat combat) {
        return game.getRules().getGameType() != GameType.Constructed || game.getRules().hasOrderCombatants()
                || Arrays.stream(GameType.values()).anyMatch(game.getRules()::hasAppliedVariant)
                || game.getPlayers().size() != 2 || player.getOpponents().size() != 1
                || !game.getStack().isEmpty() || !game.getCardsIn(ZoneType.Command).isEmpty()
                || !game.getTriggerHandler().getDelayedTriggers().isEmpty()
                || combat.getDefenders().size() != 1 || !(combat.getDefenders().get(0) instanceof Player);
    }

    private static boolean isHiddenCard(Card card, Player player) {
        return card.isFaceDown() || !card.getView().canBeShownTo(player.getView());
    }

    private static boolean hasInvalidPairEncoding(List<Integer> answer) {
        return answer == null || answer.size() % 2 != 0
                || ((List<?>) answer).stream().anyMatch(v -> !(v instanceof Integer));
    }

    private void addAssignment(Map<Card, Integer> result, int target, int amount) {
        if (hasInvalidDamageAmount(target, amount)) {
            throw new IllegalArgumentException("Invalid, negative or duplicate damage assignment");
        }
        Integer previous = result.putIfAbsent(targets.get(target), amount);
        if (previous != null) {
            throw new IllegalArgumentException("Invalid, negative or duplicate damage assignment");
        }
    }

    private boolean hasInvalidDamageAmount(int target, int amount) {
        return target < 0 || target >= targets.size() || amount < 0;
    }

    @Override public List<Integer> encodeAnswer(Object answer) {
        @SuppressWarnings("unchecked")
        Map<Card, Integer> chosen = (Map<Card, Integer>) answer;
        if (chosen == null) { throw new UnsupportedOperationException("Deferred combat damage outside vanilla scope"); }
        var result = new ArrayList<Integer>();
        for (var entry : chosen.entrySet()) {
            int index = entry.getKey() == null ? -1 : targets.indexOf(entry.getKey());
            if (index < 0 || targets.get(index) != entry.getKey() || entry.getValue() == null) {
                throw new IllegalArgumentException("Final damage map outside exact bindings");
            }
        }
        for (int i = 0; i < targets.size(); i++) {
            if (chosen.containsKey(targets.get(i))) {
                result.add(i);
                result.add(chosen.get(targets.get(i)));
            }
        }
        validate(result);
        return List.copyOf(result);
    }

    @Override public String kind() { return KIND; }

    @Override public Map<String, Object> encodeQuestion() { return data; }
}
