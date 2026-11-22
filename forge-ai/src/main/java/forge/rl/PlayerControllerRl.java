package forge.rl;

import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The Gym chooses the priority action; Forge's AI supplies its targets and execution. */
public final class PlayerControllerRl extends PlayerControllerAi {
    private final IPriorityChoices choices;
    private final GameStateReader reader;

    PlayerControllerRl(Game game, Player player, LobbyPlayerRl lobby, IPriorityChoices choices) {
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

        var sources = new CardCollection(player.getCardsIn(ZoneType.Hand));
        sources.addAll(player.getCardsIn(ZoneType.Battlefield));
        for (var source : sources) {
            var printed = source.getSpellAbilities();
            for (var ability : source.getAllPossibleAbilities(player, true)) {
                String kind = ability.isLandAbility() ? "land" : ability.isSpell() ? "spell" : "ability";
                actions.add(Map.of("kind", kind, "source", Objects.requireNonNull(reader.knownCardReference(source)),
                        "slot", printed.indexOf(ability), "description", ability.toString()));
                abilities.add(ability);
            }
        }

        while (true) {
            int index = choices.pick(observation, actions);
            if (index == 0) return null;
            var chosen = abilities.get(index - 1);
            if (!chosen.usesTargeting()) return List.of(chosen);
            // Rabid Bite's AI chooses the parent and sub-ability targets together.
            // Start with fresh targets and invoke the native chooser once on the root.
            clearTargets(chosen);
            if (chooseTargetsFor(chosen) && player.getGame().getStack().hasLegalTargeting(chosen)) {
                return List.of(chosen);
            }
            clearTargets(chosen);
            actions.remove(index);
            abilities.remove(index - 1);
        }
    }

    private static void clearTargets(SpellAbility chosen) {
        for (var node = chosen; node != null; node = node.getSubAbility()) {
            if (node.usesTargeting()) node.clearTargets();
        }
    }
}
