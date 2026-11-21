package forge.rl;

import forge.LobbyPlayer;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import java.util.List;
import java.util.Map;

/** Observes the original AI's choices; native selection and execution remain unchanged. */
public final class PlayerControllerTeacher extends PlayerControllerAi {
    private final ITeacherRecorder recorder;
    private final GameStateReader reader;

    public PlayerControllerTeacher(
            Game game, Player player, LobbyPlayer lobby, ITeacherRecorder recorder) {
        super(game, player, lobby);
        this.reader = new GameStateReader(game, player);
        this.recorder = recorder;
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        Map<String, Object> observation = reader.snapshot();
        List<SpellAbility> chosen = super.chooseSpellAbilityToPlay();
        List<Map<String, String>> choice = chosen == null || chosen.isEmpty()
                ? List.of(Map.of("kind", "pass"))
                : chosen.stream().map(this::describeChoice).toList();
        recorder.record(Map.of("observation", observation, "choice", choice));
        return chosen;
    }

    private Map<String, String> describeChoice(SpellAbility ability) {
        String source = reader.knownCardReference(ability.getHostCard());
        if (source == null) {
            throw new IllegalStateException("Chosen ability has no known source in the pre-choice snapshot");
        }
        String kind = "ability";
        if (ability.isLandAbility()) {
            kind = "land";
        } else if (ability.isSpell()) {
            kind = "spell";
        }
        return Map.of("kind", kind, "source", source);
    }
}
