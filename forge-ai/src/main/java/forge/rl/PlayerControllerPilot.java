package forge.rl;

import forge.ai.PlayerControllerAi;
import forge.card.mana.ManaAtom;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.cost.CostAdjustment;
import forge.game.mana.Mana;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetRestrictions;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Priority and spell-target choices for Forest's Might and Dragon's Fire. */
public final class PlayerControllerPilot extends PlayerControllerAi {
    private final IPilotChoices choices;
    private final GameStateReader reader;

    PlayerControllerPilot(Game game, Player player, LobbyPlayerPilot lobby, IPilotChoices choices) {
        super(game, player, lobby);
        this.choices = choices;
        reader = new GameStateReader(game, player);
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        var observation = reader.snapshot();
        var actions = new ArrayList<Map<String, Object>>();
        var abilities = new ArrayList<SpellAbility>();
        actions.add(Map.of("kind", "pass"));

        var mana = availableMana();
        var sources = new CardCollection(player.getCardsIn(ZoneType.Hand));
        sources.addAll(player.getCardsIn(ZoneType.Battlefield));
        for (Card source : sources) {
            var printed = source.getSpellAbilities();
            for (int slot = 0; slot < printed.size(); slot++) {
                var template = printed.get(slot);
                if (!template.isSpell() && !template.isLandAbility() && !template.isActivatedAbility()) continue;
                var ability = template.copy(source, player, true);
                if (!ability.canPlay() || !ability.checkRestrictions(source, player)) continue;
                if (!ability.isLandAbility() && !canAfford(ability, mana)) continue;
                if (!hasTargets(ability)) continue;
                String kind = ability.isLandAbility() ? "land" : ability.isSpell() ? "spell" : "ability";
                actions.add(Map.of("kind", kind, "source", Objects.requireNonNull(reader.knownCardReference(source)),
                        "slot", slot, "description", template.toString()));
                abilities.add(template);
            }
        }

        int index = choices.pick("priority", observation, actions);
        if (index == 0) return null;
        var chosen = abilities.get(index - 1);
        chosen.setActivatingPlayer(player);
        var targets = new ArrayList<Map<String, Object>>();
        for (var node = chosen; node != null; node = node.getSubAbility()) {
            if (!node.usesTargeting()) continue;
            node.resetTargets();
            var candidates = node.getTargetRestrictions().getAllCandidates(node);
            var options = candidates.stream().map(this::targetAction).toList();
            var context = new LinkedHashMap<>(observation);
            context.put("decision_context", Map.of("action", actions.get(index), "targets", List.copyOf(targets)));
            int target = choices.pick("target", context, options);
            node.getTargets().add(candidates.get(target));
            targets.add(options.get(target));
        }
        return List.of(chosen);
    }

    @Override
    public boolean playChosenSpellAbility(SpellAbility ability) {
        if (ability.isLandAbility()) return super.playChosenSpellAbility(ability);
        if (!playWithRollback(ability, null)) {
            throw new IllegalStateException("Forge failed to play offered ability: " + ability);
        }
        return true;
    }

    // Every mana source in these decks taps for one fixed, unrestricted color.
    private List<Mana> availableMana() {
        var available = new ArrayList<Mana>();
        player.getManaPool().forEach(available::add);
        for (var card : player.getCardsIn(ZoneType.Battlefield)) {
            for (var template : card.getManaAbilities()) {
                var tap = template.copy(card, player, true);
                if (!tap.canPlay() || !tap.checkRestrictions(card, player)) continue;
                byte color = ManaAtom.fromName(tap.getParam("Produced"));
                available.add(new Mana(color, card, tap.getManaPart(), player));
                break;
            }
        }
        return available;
    }

    private boolean canAfford(SpellAbility ability, List<Mana> available) {
        var part = ability.getPayCosts().getCostMana();
        if (part == null) return true;
        var remaining = new ManaCostBeingPaid(part.getManaCostFor(ability));
        // Applies Ghalta's native cost reduction to a detached cost.
        CostAdjustment.adjust(remaining, ability, player, null, true, false);
        for (var unit : available) {
            if (remaining.isNeeded(unit, player.getManaPool())) remaining.payMana(unit, player.getManaPool());
        }
        return remaining.isPaid();
    }

    private boolean hasTargets(SpellAbility ability) {
        for (var node = ability; node != null; node = node.getSubAbility()) {
            if (!node.usesTargeting()) continue;
            // Each targeted node in these decks requires one target. Rabid Bite's
            // two pools are independent (own creature, then opponent's creature).
            node.setTargetRestrictions(new TargetRestrictions(node.getTargetRestrictions()));
            node.resetTargets();
            if (node.getTargetRestrictions().getAllCandidates(node).isEmpty()) return false;
        }
        return true;
    }

    private Map<String, Object> targetAction(GameEntity target) {
        if (target instanceof Player p) {
            return Map.of("kind", "player", "ref", "p" + player.getGame().getRegisteredPlayers().indexOf(p));
        }
        return Map.of("kind", "card", "ref", Objects.requireNonNull(reader.knownCardReference((Card) target)));
    }
}
