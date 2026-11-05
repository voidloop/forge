package forge.rl.decisions;

import forge.game.player.Player;
import forge.rl.StateReader;
import forge.rl.requests.ManaRequest;

import java.util.function.Consumer;

/** Inputs captured before preparing the native request with the recorder's reader. */
public record ManaDecision(Consumer<ManaRequest.Choice> bindChoice) implements Decision<ManaRequest> {
    @Override public ManaRequest prepare(Player player, StateReader reader) {
        return new ManaRequest(reader, player);
    }
    @Override public void afterOriginal(ManaRequest request, Object answer) {
        bindChoice.accept(answer == null ? null : request.selected());
    }
}
