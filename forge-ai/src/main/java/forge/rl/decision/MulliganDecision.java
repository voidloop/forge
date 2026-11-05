package forge.rl.decision;

import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.rl.StateReader;
import forge.rl.decision.context.DecisionContext;
import forge.rl.decision.context.MulliganContexts;
import forge.rl.decision.context.SelectionContext;

import java.util.List;
import java.util.Map;

public final class MulliganDecision implements Decision<DecisionContext> {
    private final Type type;
    private final Player first;
    private final CardCollectionView hand;
    private final int cardsToReturn;

    private MulliganDecision(Type type, Player first, CardCollectionView hand, int cardsToReturn) {
        this.type = type;
        this.first = first;
        this.hand = hand;
        this.cardsToReturn = cardsToReturn;
    }

    public static MulliganDecision keep(Player first, int cardsToReturn) {
        return new MulliganDecision(Type.KEEP, first, null, cardsToReturn);
    }

    public static MulliganDecision tuck(CardCollectionView hand, int cardsToReturn) {
        return new MulliganDecision(Type.TUCK, null, hand, cardsToReturn);
    }

    @Override public DecisionContext prepare(Player player, StateReader reader) {
        if (isKeep()) {
            return createKeepRequest(player, reader);
        }
        return createTuckRequest(player, reader);
    }

    private DecisionContext createKeepRequest(Player player, StateReader reader) {
        MulliganContexts.validateKeep(player, first, cardsToReturn);
        String kind = "mulligan_keep";
        Map<String, Object> data = Map.of("state", reader.snapshot(), "question", Map.of("kind", kind,
                "firstPlayer", reader.reference(first), "cardsToReturn", cardsToReturn,
                "items", List.of("mulligan", "keep")));
        return DecisionContext.create(kind, data, answer -> List.of((Boolean) answer ? 1 : 0));
    }

    private DecisionContext createTuckRequest(Player player, StateReader reader) {
        MulliganContexts.validateTuck(player, hand, cardsToReturn);
        return SelectionContext.create(reader, player, "mulligan_tuck", hand, "", cardsToReturn, cardsToReturn,
                false, null, null, null);
    }

    private boolean isKeep() {
        return type == Type.KEEP;
    }

    private enum Type { KEEP, TUCK }
}
