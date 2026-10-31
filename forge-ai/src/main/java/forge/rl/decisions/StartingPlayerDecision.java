package forge.rl.decisions;

import forge.rl.StateReader;

import forge.game.GameStage;
import forge.game.GameType;
import forge.game.player.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Play/draw in an ordinary duel, before opening hands or phases exist. */
public final class StartingPlayerDecision {
    public final Map<String, Object> data;
    private final DecisionRequest<Player> selection;

    public StartingPlayerDecision(Player player, StateReader view, boolean isFirstGame) {
        var game = player.getGame();
        if (game.getAge() != GameStage.BeforeMulligan || game.getPhaseHandler().getPhase() != null
                || game.getRules().getGameType() != GameType.Constructed
                || Arrays.stream(GameType.values()).anyMatch(game.getRules()::hasAppliedVariant)
                || game.getPlayers().size() != 2 || player.getOpponents().size() != 1
                || game.getPlayers().stream().anyMatch(p -> !p.getCardsIn(forge.game.zone.ZoneType.Hand).isEmpty()
                        || !p.getCardsIn(forge.game.zone.ZoneType.Command).isEmpty())) {
            throw new UnsupportedOperationException("Starting player outside ordinary pre-hand duel");
        }
        selection = new DecisionRequest<>(player, view, "starting_player", game.getPlayers(), "", 1, 1,
                false, null, null, null);
        data = Map.of("state", selection.data.get("state"), "options", Map.of("kind", "starting_player",
                "isFirstGame", isFirstGame, "items", ((Map<?, ?>) selection.data.get("options")).get("items")));
    }

    public Player apply(List<Integer> answer) { return selection.apply(answer).get(0); }
    public List<Integer> encode(Player chosen) { return selection.encode(List.of(chosen)); }
}
