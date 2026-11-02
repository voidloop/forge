package forge.rl.decisions;

import forge.rl.StateReader;
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
public final class CombatDamageDecision {
    public final Map<String, Object> data;
    private final Player player;
    private final Combat combat;
    private final Card source;
    private final GameEntity defender;
    final List<Card> targets;
    private final CardCollectionView offered;
    private final int damage;

    public CombatDamageDecision(Player player, StateReader view, Card source, CardCollectionView targets,
            CardCollectionView remaining, int damage, GameEntity defender, boolean overrideOrder) {
        this.player = player;
        this.combat = Objects.requireNonNull(player.getGame().getCombat(), "No combat for damage assignment");
        this.source = Objects.requireNonNull(source);
        this.defender = defender;
        this.offered = Objects.requireNonNull(targets);
        this.targets = List.copyOf(targets);
        this.damage = damage;
        requireContext();
        if (!overrideOrder || (remaining == null) != (defender == null)
                || remaining != null && (remaining.stream().noneMatch(c -> c == source)
                || remaining.stream().anyMatch(c -> !combat.isAttacking(c)
                || player.getGame().getCardState(c, null) != c))) {
            throw new IllegalArgumentException("Invalid native damage assignment arguments");
        }
        data = Map.of("state", view.snapshot(), "options", StateReader.data(
                "kind", "combat_damage", "scope", "ordinary_vanilla_duel", "source", view.reference(source),
                "defender", defender == null ? null : view.reference(defender), "damage", damage,
                "items", targets.stream().map(c -> Map.of("key", view.reference(c), "name", c.getName())).toList()));
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
        if (game.getAge() != GameStage.Play || game.isGameOver() || game.getCombat() != combat
                || !game.getPhaseHandler().is(PhaseType.COMBAT_DAMAGE, combat.getAttackingPlayer())
                || source.getController() != player || game.getCardState(source, null) != source
                || !source.isInZone(ZoneType.Battlefield) || damage < 0 || source.getNetCombatDamage() != damage) {
            throw new IllegalArgumentException("Invalid or stale native combat damage context");
        }
        requireScope(player);
        if (targets.isEmpty() || new HashSet<>(targets).size() != targets.size()) {
            throw new IllegalArgumentException("Empty or duplicate damage targets");
        }
        if (defender != null) {
            if (player != combat.getAttackingPlayer() || !combat.isAttacking(source)
                    || defender != player.getOpponents().get(0) || combat.getDefenderByAttacker(source) != defender) {
                throw new IllegalArgumentException("Unbound attacking damage assignment");
            }
            requireSame(targets, combat.getBlockers(source));
        } else {
            if (player == combat.getAttackingPlayer() || !combat.isBlocking(source) || targets.size() != 1) {
                throw new IllegalArgumentException("Unbound blocking damage assignment");
            }
            requireSame(targets, combat.getAttackersBlockedBy(source));
        }
        requireSame(targets, offered);
        for (Card card : targets) {
            if (game.getCardState(card, null) != card || !card.isInZone(ZoneType.Battlefield)
                    || card.getController() != player.getOpponents().get(0)) {
                throw new IllegalArgumentException("Foreign or stale damage target");
            }
        }
    }

    static void requireScope(Player player) {
        var game = player.getGame();
        var combat = game.getCombat();
        if (game.getAge() != GameStage.Play || game.isGameOver() || combat == null
                || !game.getPhaseHandler().is(PhaseType.COMBAT_DAMAGE, combat.getAttackingPlayer())) {
            throw new IllegalArgumentException("Invalid native combat damage phase");
        }
        // shortcut: current unordered vanilla duel only; legacy orders and keywords need their own proof.
        if (game.getRules().getGameType() != GameType.Constructed || game.getRules().hasOrderCombatants()
                || Arrays.stream(GameType.values()).anyMatch(game.getRules()::hasAppliedVariant)
                || game.getPlayers().size() != 2 || player.getOpponents().size() != 1
                || !game.getStack().isEmpty() || !game.getCardsIn(ZoneType.Command).isEmpty()
                || !game.getTriggerHandler().getDelayedTriggers().isEmpty()
                || combat.getDefenders().size() != 1 || !(combat.getDefenders().get(0) instanceof Player)) {
            throw new UnsupportedOperationException("Ordinary combat damage scope");
        }
        for (Card card : game.getCardsIn(ZoneType.Battlefield)) {
            if (card.isFaceDown() || !card.getView().canBeShownTo(player.getView())) {
                throw new UnsupportedOperationException("Combat damage visibility");
            }
            if (card.isBasicLand()) { PriorityDecision.requireBasicLand(card); }
            else { PriorityDecision.requireCreature(card); }
        }
    }

    void validate(List<Integer> answer) {
        requireContext();
        if (answer == null || answer.size() % 2 != 0
                || ((List<?>) answer).stream().anyMatch(v -> !(v instanceof Integer))) {
            throw new IllegalArgumentException("Invalid combat damage pairs");
        }
        Map<Card, Integer> result = new LinkedHashMap<>();
        long total = 0;
        for (int i = 0; i < answer.size(); i += 2) {
            int target = answer.get(i), amount = answer.get(i + 1);
            if (target < 0 || target >= targets.size() || amount < 0
                    || result.putIfAbsent(targets.get(target), amount) != null) {
                throw new IllegalArgumentException("Invalid, negative or duplicate damage assignment");
            }
            total += amount;
        }
        if (total != damage) { throw new IllegalArgumentException("Combat damage must be assigned in full"); }
    }
}
