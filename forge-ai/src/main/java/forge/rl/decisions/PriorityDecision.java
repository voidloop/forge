package forge.rl.decisions;

import forge.game.player.Player;
import forge.rl.StateReader;
import forge.rl.requests.PriorityRequest;

import java.util.function.Consumer;

/** Inputs captured before preparing the native request with the recorder's reader. */
public record PriorityDecision(Consumer<PriorityRequest.Choice> bindChoice) implements Decision<PriorityRequest> {
    @Override public PriorityRequest prepare(Player player, StateReader reader) {
        return new PriorityRequest(reader, player);
    }
    @Override public void afterOriginal(PriorityRequest request, Object answer) {
        bindChoice.accept(request.bindChoice(request.encodeAnswer(answer)));
    }
}
