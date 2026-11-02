package forge.rl.decisions;

import forge.rl.StateReader;
import forge.game.GameEntity;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import java.util.*;

/** Final attacker/defender assignments, bound before the native void decision. */
public final class AttackersDecision {
    public final Map<String, Object> data;
    private final Player player;
    final Combat combat;
    final List<Card> attackers;
    final List<GameEntity> defenders;

    void requireContext() {
        var game = player.getGame();
        if (game.getAge() != GameStage.Play || game.isGameOver()
                || !game.getPhaseHandler().is(PhaseType.COMBAT_DECLARE_ATTACKERS, player)
                || game.getCombat() != combat || combat.getAttackingPlayer() != player) {
            throw new IllegalArgumentException("Invalid native attacker declaration context");
        }
        // shortcut: ordinary vanilla duel only; extend combat costs/keywords after their own proof.
        if (game.getRules().getGameType() != GameType.Constructed
                || Arrays.stream(GameType.values()).anyMatch(game.getRules()::hasAppliedVariant)
                || game.getPlayers().size() != 2 || player.getOpponents().size() != 1
                || player.getDeclaresAttackers() != null || !game.getStack().isEmpty()
                || !game.getCardsIn(ZoneType.Command).isEmpty()
                || !game.getTriggerHandler().getDelayedTriggers().isEmpty()
                || combat.getDefenders().size() != 1 || combat.getDefenders().get(0) != player.getOpponents().get(0)) {
            throw new UnsupportedOperationException("Ordinary attacker declaration scope");
        }
        for (Card card : game.getCardsIn(ZoneType.Battlefield)) {
            if (card.isFaceDown() || !card.getView().canBeShownTo(player.getView())) {
                throw new UnsupportedOperationException("Attacker declaration visibility");
            }
            if (card.isBasicLand()) { PriorityDecision.requireBasicLand(card); }
            else { PriorityDecision.requireCreature(card); }
        }
    }

    public AttackersDecision(Player player, StateReader view, Player attacker, Combat combat) {
        this.player = player;
        this.combat = Objects.requireNonNull(combat);
        requireContext();
        if (attacker != player || !combat.getAttackers().isEmpty()) {
            throw new IllegalArgumentException("Unbound or nonempty attacker declaration");
        }
        attackers = List.copyOf(CombatUtil.getPossibleAttackers(player));
        defenders = List.copyOf(combat.getDefenders());
        var items = new ArrayList<Map<String, Object>>();
        for (Card card : attackers) {
            var legal = new ArrayList<Integer>();
            for (int i = 0; i < defenders.size(); i++) {
                if (CombatUtil.canAttack(card, defenders.get(i))) {
                    if (CombatUtil.getAttackCost(player.getGame(), card, defenders.get(i)) != null) {
                        throw new UnsupportedOperationException("Attacker declaration cost");
                    }
                    legal.add(i);
                }
            }
            items.add(Map.of("key", view.reference(card), "name", card.getName(), "defenders", List.copyOf(legal)));
        }
        data = Map.of("state", view.snapshot(), "options", Map.of("kind", "declare_attackers",
                "scope", "ordinary_vanilla_duel", "items", List.copyOf(items),
                "defenders", defenders.stream().map(d -> Map.of("key", view.reference(d), "name", d.getName())).toList()));
    }

    List<Card> validate(List<Integer> answer) {
        if (answer == null || answer.size() % 2 != 0
                || ((List<?>) answer).stream().anyMatch(value -> !(value instanceof Integer))) {
            throw new IllegalArgumentException("Invalid attacker/defender pairs");
        }
        var chosen = new ArrayList<Card>();
        for (int i = 0; i < answer.size(); i += 2) {
            int a = answer.get(i), d = answer.get(i + 1);
            if (a < 0 || a >= attackers.size() || d < 0 || d >= defenders.size()) {
                throw new IllegalArgumentException("Invalid attacker/defender index");
            }
            Card card = attackers.get(a);
            if (chosen.contains(card) || player.getGame().getCardState(card, null) != card
                    || card.getController() != player || !card.isInZone(ZoneType.Battlefield)
                    || !CombatUtil.canAttack(card, defenders.get(d))) {
                throw new IllegalArgumentException("Duplicate, stale or illegal attacker");
            }
            chosen.add(card);
        }
        return chosen;
    }

}
