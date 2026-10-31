package forge.rl;

import forge.game.GameStage;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One restricted priority question; legality and execution remain native. */
final class BasicLandDecision {
    final Map<String, Object> data;
    private final Player player;
    private final List<Choice> bindings = new ArrayList<>();

    record Choice(SpellAbility ability, Card card) {
        int before(Player player, SpellAbility selected) {
            requireContext(player);
            if (selected != ability || ability.getHostCard() != card) {
                throw new IllegalArgumentException("Unbound land action");
            }
            if (player.getGame().getCardState(card, null) != card || !card.isInZone(ZoneType.Hand) || !ability.canPlay()) {
                throw new IllegalArgumentException("Selected land is no longer playable");
            }
            return player.getLandsPlayedThisTurn();
        }

        void after(Player player, int played, boolean reported) {
            Card current = player.getGame().getCardState(card, null);
            if (!reported || current == null || !current.isInZone(ZoneType.Battlefield) || current.getController() != player
                    || player.getLandsPlayedThisTurn() != played + 1) {
                throw new IllegalStateException("Native land execution did not complete");
            }
        }
    }

    static void requireContext(Player player) {
        var game = player.getGame();
        if (game.getAge() != GameStage.Play || game.isGameOver() || game.getPhaseHandler().getPriorityPlayer() != player) {
            throw new IllegalStateException("Player does not have live priority");
        }
        // shortcut: pass/basic-land fixture only; prove other priority families separately.
        if (!game.getStack().isEmpty() || !game.getCardsIn(ZoneType.Command).isEmpty()) {
            throw new UnsupportedOperationException("basic-land priority context");
        }
        for (Card card : game.getCardsIn(ZoneType.Battlefield)) {
            if (!card.isBasicLand() || card.isFaceDown()) {
                throw new UnsupportedOperationException("basic-land priority battlefield");
            }
        }
        for (Card card : player.getCardsIn(ZoneType.Hand)) {
            if (!card.isBasicLand() || card.isFaceDown() || !card.getView().canBeShownTo(player.getView())) {
                throw new UnsupportedOperationException("basic-land priority hand");
            }
        }
    }

    BasicLandDecision(Player player, StateReader view) {
        this.player = player;
        requireContext(player);
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(Map.of("type", "pass"));
        for (Card card : player.getCardsIn(ZoneType.Hand)) {
            for (SpellAbility ability : card.getSpellAbilities()) {
                if (!ability.isLandAbility()) { continue; }
                Player activator = ability.getActivatingPlayer();
                boolean playable;
                ability.setActivatingPlayer(player);
                try { playable = ability.canPlay(); }
                finally { ability.setActivatingPlayer(activator); }
                if (!playable) { continue; }
                if (bindings.stream().anyMatch(bound -> bound.card() == card)) {
                    throw new UnsupportedOperationException("Multiple land variants for one basic land");
                }
                bindings.add(new Choice(ability, card));
                items.add(Map.of("type", "play_land", "name", card.getName(), "zone", "Hand", "key", view.reference(card)));
            }
        }
        data = Map.of("state", view.snapshot(), "options", Map.of("kind", "priority_basic_lands",
                "scope", "pass_or_basic_land_from_hand", "items", List.copyOf(items)));
    }

    Choice apply(List<Integer> answer) {
        Objects.requireNonNull(answer, "Missing priority response");
        if (answer.size() != 1 || !(((List<?>) answer).get(0) instanceof Integer)
                || answer.get(0) < 0 || answer.get(0) > bindings.size()) {
            throw new IllegalArgumentException("Invalid basic-land priority response");
        }
        if (answer.get(0) == 0) { return null; }
        Choice choice = bindings.get(answer.get(0) - 1);
        choice.ability().setActivatingPlayer(player);
        return choice;
    }

    List<Integer> encode(List<SpellAbility> chosen) {
        if (chosen == null) { return List.of(0); }
        if (chosen.size() != 1) { throw new IllegalArgumentException("Native priority response must be one land or null"); }
        for (int i = 0; i < bindings.size(); i++) {
            Choice choice = bindings.get(i);
            if (chosen.get(0) == choice.ability() && chosen.get(0).getHostCard() == choice.card()) { return List.of(i + 1); }
        }
        throw new IllegalArgumentException("Native priority response outside request bindings");
    }
}
