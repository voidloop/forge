package forge.rl.decisions;

import forge.game.GameEntity;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.rl.StateReader;
import forge.rl.requests.DecisionRequest;
import forge.rl.requests.SelectionRequest;

import java.util.Map;

public final class SelectionDecision<T extends GameEntity> implements Decision {
    private final String kind;
    private final Iterable<T> options;
    private final String title;
    private final int minimum;
    private final int maximum;
    private final boolean optional;
    private final DelayedReveal reveal;
    private final Player related;
    private final Map<String, Object> parameters;

    private SelectionDecision(String kind, Iterable<T> options, String title, int minimum, int maximum,
            boolean optional, DelayedReveal reveal, Player related, Map<String, Object> parameters) {
        this.kind = kind;
        this.options = options;
        this.title = title;
        this.minimum = minimum;
        this.maximum = maximum;
        this.optional = optional;
        this.reveal = reveal;
        this.related = related;
        this.parameters = parameters;
    }

    public static <T extends GameEntity> SelectionDecision<T> singleEntity(Iterable<T> options, String title,
            boolean optional, DelayedReveal reveal, Player related, Map<String, Object> parameters) {
        return new SelectionDecision<>("single_entity", options, title, 1, 1, optional, reveal, related, parameters);
    }

    public static SelectionDecision<forge.game.card.Card> cards(Iterable<forge.game.card.Card> options, String title,
            int minimum, int maximum, boolean optional, Map<String, Object> parameters) {
        return new SelectionDecision<>("cards", options, title, minimum, maximum, optional, null, null, parameters);
    }

    public static <T extends GameEntity> SelectionDecision<T> entities(Iterable<T> options, String title, int minimum,
            int maximum, DelayedReveal reveal, Player related, Map<String, Object> parameters) {
        return new SelectionDecision<>("entities", options, title, minimum, maximum, minimum == 0, reveal, related,
                parameters);
    }

    @Override public DecisionRequest prepare(Player player, StateReader reader) {
        return SelectionRequest.create(reader, player, kind, options, title, minimum, maximum, optional, reveal,
                related, parameters);
    }
}
