package forge.rl.requests;

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
public final class BlockersRequest implements DecisionRequest {
    public final Map<String, Object> data;
    private final Player player;
    final Combat combat;
    final List<Card> attackers;
    final List<Card> blockers = new ArrayList<>();

    void requireContext() {
        var game = player.getGame();
        if (hasInvalidDeclarationContext()) {
            throw new IllegalArgumentException("Invalid native blocker declaration context");
        }
        // shortcut: ordinary vanilla duel only; extend combat costs/keywords after their own proof.
        if (isOutsideDeclarationScope()) {
            throw new UnsupportedOperationException("Ordinary blocker declaration scope");
        }
        for (Card card : game.getCardsIn(ZoneType.Battlefield)) {
            if (isHiddenCard(card)) {
                throw new UnsupportedOperationException("Blocker declaration visibility");
            }
            if (card.isBasicLand()) { PriorityRequest.requireBasicLand(card); }
            else { PriorityRequest.requireCreature(card); }
        }
    }

    void requireAttackers() {
        List<Card> current = combat.getAttackers();
        if (current.size() != attackers.size()) { throw new IllegalArgumentException("Attackers changed during decision"); }
        for (int i = 0; i < attackers.size(); i++) {
            Card card = attackers.get(i);
            if (isStaleAttacker(card, current.get(i))) {
                throw new IllegalArgumentException("Stale or unbound attacking configuration");
            }
        }
    }

    public BlockersRequest(Player player, Player defender, Combat combat) {
        this.player = player;
        this.combat = Objects.requireNonNull(combat);
        requireContext();
        if (hasUnboundDeclaration(defender)) {
            throw new IllegalArgumentException("Unbound or nonempty blocker declaration");
        }
        try (var view = new StateReader(player, 0L)) {
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
            data = Map.of("state", view.snapshot(), "question", Map.of("kind", "declare_blockers",
                    "scope", "ordinary_vanilla_duel", "items", List.copyOf(items),
                    "attackers", attackers.stream().map(a -> Map.of("key", view.reference(a), "name", a.getName())).toList()));
        }
    }

    void validate(List<Integer> answer) {
        if (hasInvalidPairEncoding(answer)) {
            throw new IllegalArgumentException("Invalid blocker/attacker pairs");
        }
        var chosen = new HashSet<Card>();
        for (int i = 0; i < answer.size(); i += 2) {
            int b = answer.get(i), a = answer.get(i + 1);
            if (isOutsidePairBindings(b, a)) {
                throw new IllegalArgumentException("Invalid blocker/attacker index");
            }
            Card card = blockers.get(b);
            boolean firstBlock = chosen.add(card);
            if (isIllegalBlocker(card, a, firstBlock)) {
                throw new IllegalArgumentException("Duplicate, stale or illegal blocker");
            }
        }
    }

    void validateNative() {
        String error = CombatUtil.validateBlocks(combat, player);
        if (error != null) { throw new IllegalArgumentException("Native block declaration invalid: " + error); }
    }

    private boolean hasInvalidDeclarationContext() {
        var game = player.getGame();
        return game.getAge() != GameStage.Play || game.isGameOver()
                || game.getCombat() != combat || combat.getAttackingPlayer() == player
                || !game.getPhaseHandler().is(PhaseType.COMBAT_DECLARE_BLOCKERS, combat.getAttackingPlayer());
    }

    private boolean isOutsideDeclarationScope() {
        var game = player.getGame();
        return game.getRules().getGameType() != GameType.Constructed
                || Arrays.stream(GameType.values()).anyMatch(game.getRules()::hasAppliedVariant)
                || game.getPlayers().size() != 2 || player.getOpponents().size() != 1
                || combat.getAttackingPlayer() != player.getOpponents().get(0)
                || player.getDeclaresBlockers() != null || !game.getStack().isEmpty()
                || !game.getCardsIn(ZoneType.Command).isEmpty()
                || !game.getTriggerHandler().getDelayedTriggers().isEmpty()
                || combat.getDefenders().size() != 1 || combat.getDefenders().get(0) != player;
    }

    private boolean isHiddenCard(Card card) {
        return card.isFaceDown() || !card.getView().canBeShownTo(player.getView());
    }

    private static boolean hasInvalidPairEncoding(List<Integer> answer) {
        return answer == null || answer.size() % 2 != 0
                || ((List<?>) answer).stream().anyMatch(value -> !(value instanceof Integer));
    }

    private boolean isStaleAttacker(Card card, Card current) {
        return current != card || player.getGame().getCardState(card, null) != card
                || card.getController() != combat.getAttackingPlayer() || !card.isInZone(ZoneType.Battlefield)
                || combat.getDefenderByAttacker(card) != player;
    }

    private boolean hasUnboundDeclaration(Player defender) {
        return defender != player || !combat.getAllBlockers().isEmpty() || combat.getAttackers().isEmpty();
    }

    private boolean isOutsidePairBindings(int b, int a) {
        return b < 0 || b >= blockers.size() || a < 0 || a >= attackers.size();
    }

    private boolean isIllegalBlocker(Card card, int a, boolean firstBlock) {
        return !firstBlock || player.getGame().getCardState(card, null) != card
                || card.getController() != player || !card.isInZone(ZoneType.Battlefield)
                || !CombatUtil.canBlock(attackers.get(a), card);
    }

    @Override public List<Integer> encodeAnswer(Object answer) {
        requireContext(); requireAttackers();
        var result = new ArrayList<Integer>();
        for (int a = 0; a < attackers.size(); a++) {
            for (Card card : combat.getBlockers(attackers.get(a))) {
                int b = blockers.indexOf(card);
                if (b < 0 || blockers.get(b) != card) {
                    throw new IllegalArgumentException("Final block declaration outside exact bindings");
                }
                result.add(b); result.add(a);
            }
        }
        validate(result); validateNative();
        return List.copyOf(result);
    }

    @Override public Map<String, Object> data() { return data; }
}
