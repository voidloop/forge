package forge.rl.decision.context;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.SpellAbility;
import forge.rl.StateReader;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A boolean confirmation, bound to this invocation's exact native ability. */
public final class ConfirmActionContext implements DecisionContext {
    private static final String KIND = "confirm";

    private final Player player;
    private final SpellAbility ability;
    private final Card source;
    private final Player activator;
    private final Map<String, Object> data;

    public ConfirmActionContext(Player player, SpellAbility ability,
                                PlayerActionConfirmMode mode, String message, List<String> options, Card cardToShow,
                                Map<String, Object> params) {
        this(prepareWithLocalReader(player, ability, mode, message, options, cardToShow, params));
    }

    public ConfirmActionContext(StateReader reader, Player player, SpellAbility ability,
                                PlayerActionConfirmMode mode, String message, List<String> options, Card cardToShow,
                                Map<String, Object> params) {
        this(prepare(reader, player, ability, mode, message, options, cardToShow, params));
    }

    private ConfirmActionContext(Prepared prepared) {
        player = prepared.player();
        ability = prepared.ability();
        source = prepared.source();
        activator = prepared.activator();
        data = prepared.data();
    }

    private static Prepared prepareWithLocalReader(Player player, SpellAbility ability,
            PlayerActionConfirmMode mode, String message, List<String> options, Card cardToShow,
            Map<String, Object> params) {
        try (var reader = new StateReader(player, 0L)) {
            return prepare(reader, player, ability, mode, message, options, cardToShow, params);
        }
    }

    private static Prepared prepare(StateReader reader, Player player, SpellAbility ability,
            PlayerActionConfirmMode mode, String message, List<String> options, Card cardToShow,
            Map<String, Object> params) {
        Card source = ability == null ? null : ability.getHostCard();
        Player activator = ability == null ? null : ability.getActivatingPlayer();
        if (hasUnsupportedMetadata(options, cardToShow, params)) {
            throw new UnsupportedOperationException("confirmAction with extra options/card/parameters");
        }
        requireBinding(player, ability, source, activator);
        Map<String, Object> facts = reader.snapshot();
        Map<String, Object> context = ability == null ? null : StateReader.data(
                "source", reader.reference(source), "api", Objects.toString(ability.getApi(), ""),
                "description", Objects.toString(ability.getDescription(), ""),
                "activatingPlayer", activator == null ? null : reader.reference(activator));
        Map<String, Object> data = Map.of("state", facts, "question", StateReader.data("kind", KIND,
                "prompt", Objects.toString(message, ""), "choice", Objects.toString(mode, ""),
                "ability", context));
        return new Prepared(player, ability, source, activator, data);
    }

    @Override public List<Integer> encodeAnswer(Object answer) {
        requireBinding();
        return List.of(Objects.requireNonNull((Boolean) answer, "Missing native confirmation") ? 1 : 0);
    }

    @Override public String kind() { return KIND; }

    @Override public Map<String, Object> encodeQuestion() { return data; }

    void requireBinding() {
        requireBinding(player, ability, source, activator);
    }

    private static void requireBinding(Player player, SpellAbility ability, Card source, Player activator) {
        if (ability != null && hasUnboundAbility(player, ability, source, activator)) {
            throw new UnsupportedOperationException("Hidden, stale or foreign confirmation ability");
        }
    }

    private static boolean hasUnsupportedMetadata(List<String> options, Card cardToShow, Map<String, Object> params) {
        return options == null || !options.isEmpty() || cardToShow != null || params != null && !params.isEmpty();
    }

    private static boolean hasUnboundAbility(Player player, SpellAbility ability, Card source, Player activator) {
        return source == null || ability.getHostCard() != source || source.getGame() != player.getGame()
                || source.getZone() == null || source.isFaceDown()
                || source.getZone().getCards().stream().noneMatch(current -> current == source)
                || !source.getView().canBeShownTo(player.getView())
                || ability.getActivatingPlayer() != activator
                || activator != null && player.getGame().getPlayers().stream().noneMatch(current -> current == activator);
    }

    private record Prepared(Player player, SpellAbility ability, Card source, Player activator,
                            Map<String, Object> data) { }
}
