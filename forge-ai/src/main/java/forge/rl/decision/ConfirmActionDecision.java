package forge.rl.decision;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.SpellAbility;
import forge.rl.StateReader;
import forge.rl.decision.context.ConfirmActionContext;
import forge.rl.decision.context.DecisionContext;

import java.util.List;
import java.util.Map;

/** The teacher's plan for one native boolean confirmation. */
public final class ConfirmActionDecision implements Decision<DecisionContext> {
    private final SpellAbility ability;
    private final PlayerActionConfirmMode mode;
    private final String message;
    private final List<String> options;
    private final Card cardToShow;
    private final Map<String, Object> parameters;

    public ConfirmActionDecision(SpellAbility ability, PlayerActionConfirmMode mode, String message,
            List<String> options, Card cardToShow, Map<String, Object> parameters) {
        this.ability = ability;
        this.mode = mode;
        this.message = message;
        this.options = options;
        this.cardToShow = cardToShow;
        this.parameters = parameters;
    }

    @Override public SpellAbility ability() { return ability; }

    @Override public DecisionContext prepare(Player player, StateReader reader) {
        return new ConfirmActionContext(reader, player, ability, mode, message, options, cardToShow, parameters);
    }
}
