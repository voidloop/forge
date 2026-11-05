package forge.rl.decisions;

import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.rl.StateReader;
import forge.rl.requests.DecisionRequest;
import forge.rl.requests.MulliganRequests;
import forge.rl.requests.SelectionRequest;

import java.util.List;
import java.util.Map;

public final class MulliganDecision implements Decision {
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

    @Override public DecisionRequest prepare(Player player, StateReader reader) {
        if (isKeep()) {
            return createKeepRequest(player, reader);
        }
        return createTuckRequest(player, reader);
    }

    private DecisionRequest createKeepRequest(Player player, StateReader reader) {
        MulliganRequests.validateKeep(player, first, cardsToReturn);
        Map<String, Object> data = Map.of("state", reader.snapshot(), "question", Map.of("kind", "mulligan_keep",
                "firstPlayer", reader.reference(first), "cardsToReturn", cardsToReturn,
                "items", List.of("mulligan", "keep")));
        return DecisionRequest.create(data, answer -> List.of((Boolean) answer ? 1 : 0));
    }

    private DecisionRequest createTuckRequest(Player player, StateReader reader) {
        MulliganRequests.validateTuck(player, hand, cardsToReturn);
        return SelectionRequest.create(reader, player, "mulligan_tuck", hand, "", cardsToReturn, cardsToReturn,
                false, null, null, null);
    }

    private boolean isKeep() {
        return type == Type.KEEP;
    }

    private enum Type { KEEP, TUCK }
}
