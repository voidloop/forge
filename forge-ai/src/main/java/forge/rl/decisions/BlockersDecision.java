package forge.rl.decisions;

import forge.rl.StateReader;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import java.util.*;

/** Final blocker/attacker assignments, bound before the native void decision. */
public final class BlockersDecision {
    public final Map<String, Object> data;
    private final Player player;
    private final Combat combat;
    private final List<Card> attackers;
    private final List<Card> blockers = new ArrayList<>();

    private void requireContext() {
        var game = player.getGame();
        if (game.getAge() != GameStage.Play || game.isGameOver()
                || game.getCombat() != combat || combat.getAttackingPlayer() == player
                || !game.getPhaseHandler().is(PhaseType.COMBAT_DECLARE_BLOCKERS, combat.getAttackingPlayer())) {
            throw new IllegalArgumentException("Invalid native blocker declaration context");
        }
        // shortcut: ordinary vanilla duel only; extend combat costs/keywords after their own proof.
        if (game.getRules().getGameType() != GameType.Constructed
                || Arrays.stream(GameType.values()).anyMatch(game.getRules()::hasAppliedVariant)
                || game.getPlayers().size() != 2 || player.getOpponents().size() != 1
                || combat.getAttackingPlayer() != player.getOpponents().get(0)
                || player.getDeclaresBlockers() != null || !game.getStack().isEmpty()
                || !game.getCardsIn(ZoneType.Command).isEmpty()
                || !game.getTriggerHandler().getDelayedTriggers().isEmpty()
                || combat.getDefenders().size() != 1 || combat.getDefenders().get(0) != player) {
            throw new UnsupportedOperationException("Ordinary blocker declaration scope");
        }
        for (Card card : game.getCardsIn(ZoneType.Battlefield)) {
            if (card.isFaceDown() || !card.getView().canBeShownTo(player.getView())) {
                throw new UnsupportedOperationException("Blocker declaration visibility");
            }
            if (card.isBasicLand()) { PriorityDecision.requireBasicLand(card); }
            else { PriorityDecision.requireCreature(card); }
        }
    }

    private void requireAttackers() {
        List<Card> current = combat.getAttackers();
        if (current.size() != attackers.size()) { throw new IllegalArgumentException("Attackers changed during decision"); }
        for (int i = 0; i < attackers.size(); i++) {
            Card card = attackers.get(i);
            if (current.get(i) != card || player.getGame().getCardState(card, null) != card
                    || card.getController() != combat.getAttackingPlayer() || !card.isInZone(ZoneType.Battlefield)
                    || combat.getDefenderByAttacker(card) != player) {
                throw new IllegalArgumentException("Stale or unbound attacking configuration");
            }
        }
    }

    public BlockersDecision(Player player, StateReader view, Player defender, Combat combat) {
        this.player = player;
        this.combat = Objects.requireNonNull(combat);
        requireContext();
        if (defender != player || !combat.getAllBlockers().isEmpty() || combat.getAttackers().isEmpty()) {
            throw new IllegalArgumentException("Unbound or nonempty blocker declaration");
        }
        attackers = List.copyOf(combat.getAttackers());
        requireAttackers();
        var items = new ArrayList<Map<String, Object>>();
        for (Card card : player.getCreaturesInPlay()) {
            var legal = new ArrayList<Integer>();
            for (int i = 0; i < attackers.size(); i++) {
                if (CombatUtil.canBlock(attackers.get(i), card, combat)) {
                    if (CombatUtil.getBlockCost(player.getGame(), card, attackers.get(i)) != null) {
                        throw new UnsupportedOperationException("Blocker declaration cost");
                    }
                    legal.add(i);
                }
            }
            if (!legal.isEmpty()) {
                blockers.add(card);
                items.add(Map.of("key", view.reference(card), "name", card.getName(), "attackers", List.copyOf(legal)));
            }
        }
        data = Map.of("state", view.snapshot(), "options", Map.of("kind", "declare_blockers",
                "scope", "ordinary_vanilla_duel", "items", List.copyOf(items),
                "attackers", attackers.stream().map(a -> Map.of("key", view.reference(a), "name", a.getName())).toList()));
    }

    private void validate(List<Integer> answer) {
        if (answer == null || answer.size() % 2 != 0
                || ((List<?>) answer).stream().anyMatch(value -> !(value instanceof Integer))) {
            throw new IllegalArgumentException("Invalid blocker/attacker pairs");
        }
        var chosen = new HashSet<Card>();
        for (int i = 0; i < answer.size(); i += 2) {
            int b = answer.get(i), a = answer.get(i + 1);
            if (b < 0 || b >= blockers.size() || a < 0 || a >= attackers.size()) {
                throw new IllegalArgumentException("Invalid blocker/attacker index");
            }
            Card card = blockers.get(b);
            if (!chosen.add(card) || player.getGame().getCardState(card, null) != card
                    || card.getController() != player || !card.isInZone(ZoneType.Battlefield)
                    || !CombatUtil.canBlock(attackers.get(a), card)) {
                throw new IllegalArgumentException("Duplicate, stale or illegal blocker");
            }
        }
    }

    private void validateNative() {
        String error = CombatUtil.validateBlocks(combat, player);
        if (error != null) { throw new IllegalArgumentException("Native block declaration invalid: " + error); }
    }

    public void apply(List<Integer> answer) {
        requireContext(); requireAttackers();
        if (!combat.getAllBlockers().isEmpty()) { throw new IllegalArgumentException("Combat changed during decision"); }
        validate(answer);
        for (int i = 0; i < answer.size(); i += 2) {
            Card attacker = attackers.get(answer.get(i + 1)), blocker = blockers.get(answer.get(i));
            if (!CombatUtil.canBlock(attacker, blocker, combat)) { throw new IllegalArgumentException("Native block pair invalid"); }
            combat.addBlocker(attacker, blocker);
        }
        validateNative();
    }

    public List<Integer> encode() {
        requireContext(); requireAttackers();
        var answer = new ArrayList<Integer>();
        for (int a = 0; a < attackers.size(); a++) {
            for (Card card : combat.getBlockers(attackers.get(a))) {
                int b = blockers.indexOf(card);
                if (b < 0 || blockers.get(b) != card) {
                    throw new IllegalArgumentException("Final block declaration outside exact bindings");
                }
                answer.add(b); answer.add(a);
            }
        }
        validate(answer); validateNative();
        return List.copyOf(answer);
    }
}
