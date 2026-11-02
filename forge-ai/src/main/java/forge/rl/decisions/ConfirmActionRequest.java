package forge.rl.decisions;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.SpellAbility;
import forge.rl.StateReader;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A boolean confirmation, bound to this invocation's exact native ability. */
public final class ConfirmActionRequest implements DecisionRequest {
    private final Player player;
    private final SpellAbility ability;
    private final Card source;
    private final Player activator;
    private final Map<String, Object> data;

    public ConfirmActionRequest(Player player, StateReader view, SpellAbility ability,
            PlayerActionConfirmMode mode, String message, List<String> options, Card cardToShow,
            Map<String, Object> params) {
        this.player = player;
        this.ability = ability;
        source = ability == null ? null : ability.getHostCard();
        activator = ability == null ? null : ability.getActivatingPlayer();
        if (hasUnsupportedMetadata(options, cardToShow, params)) {
            throw new UnsupportedOperationException("confirmAction with extra options/card/parameters");
        }
        requireBinding();
        Map<String, Object> facts = view.snapshot();
        Map<String, Object> context = ability == null ? null : StateReader.data(
                "source", view.reference(source), "api", Objects.toString(ability.getApi(), ""),
                "description", Objects.toString(ability.getDescription(), ""),
                "activatingPlayer", activator == null ? null : view.reference(activator));
        data = Map.of("state", facts, "options", StateReader.data("kind", "confirm",
                "prompt", Objects.toString(message, ""), "choice", Objects.toString(mode, ""),
                "ability", context));
    }

    @Override public Map<String, Object> data() { return data; }

    void requireBinding() {
        if (ability != null && hasUnboundAbility()) {
            throw new UnsupportedOperationException("Hidden, stale or foreign confirmation ability");
        }
    }

    private static boolean hasUnsupportedMetadata(List<String> options, Card cardToShow, Map<String, Object> params) {
        return options == null || !options.isEmpty() || cardToShow != null || params != null && !params.isEmpty();
    }

    private boolean hasUnboundAbility() {
        return source == null || ability.getHostCard() != source || source.getGame() != player.getGame()
                || source.getZone() == null || source.isFaceDown()
                || source.getZone().getCards().stream().noneMatch(current -> current == source)
                || !source.getView().canBeShownTo(player.getView())
                || ability.getActivatingPlayer() != activator
                || activator != null && player.getGame().getPlayers().stream().noneMatch(current -> current == activator);
    }
}
