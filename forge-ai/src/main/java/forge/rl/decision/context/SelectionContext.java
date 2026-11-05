package forge.rl.decision.context;

import forge.rl.StateReader;

import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One native selection: detached question and private, invocation-local bindings. */
public final class SelectionContext<T extends GameEntity> implements DecisionContext {
    public final Map<String, Object> data;
    private final String kind;
    final List<T> bindings;
    private final int lower;
    private final int upper;
    private final boolean optional;

    public SelectionContext(Player observer, String kind, Iterable<T> options, String title, int min, int max,
                            boolean optional, DelayedReveal reveal, Player related, Map<String, Object> params) {
        this(prepareWithLocalReader(observer, kind, options, title, min, max, optional, reveal, related, params));
    }

    public static <T extends GameEntity> SelectionContext<T> create(StateReader reader, Player observer, String kind,
            Iterable<T> options, String title, int min, int max, boolean optional, DelayedReveal reveal,
            Player related, Map<String, Object> params) {
        return new SelectionContext<>(prepare(Objects.requireNonNull(reader), observer, kind, options, title, min,
                max, optional, reveal, related, params));
    }

    private SelectionContext(Prepared<T> prepared) {
        data = prepared.data();
        kind = prepared.kind();
        bindings = prepared.bindings();
        lower = prepared.lower();
        upper = prepared.upper();
        optional = prepared.optional();
    }

    private static <T extends GameEntity> Prepared<T> prepareWithLocalReader(Player observer, String kind,
            Iterable<T> options, String title, int min, int max, boolean optional, DelayedReveal reveal,
            Player related, Map<String, Object> params) {
        try (var reader = new StateReader(observer, 0L)) {
            return prepare(reader, observer, kind, options, title, min, max, optional, reveal, related, params);
        }
    }

    private static <T extends GameEntity> Prepared<T> prepare(StateReader reader, Player observer, String kind,
            Iterable<T> options, String title, int min, int max, boolean optional, DelayedReveal reveal,
            Player related, Map<String, Object> params) {
        if (hasUnsupportedMetadata(reveal, params)) {
            throw new UnsupportedOperationException("selection reveal/parameters");
        }
        if (min < 0 || max < min) { throw new UnsupportedOperationException("selection bounds"); }
        if (hasForeignRelatedPlayer(observer, related)) {
            throw new UnsupportedOperationException("selection related player");
        }
        List<T> bindings = new ArrayList<>();
        List<Map<String, Object>> descriptions = new ArrayList<>();
        for (T entity : options) {
            if (entity instanceof Card card) {
                // shortcut: visible face-up cards only; add reveals/face-down choices after their own proof.
                if (isHiddenOrUnboundCard(card, observer)) {
                    throw new UnsupportedOperationException("selection of hidden or unbound card");
                }
                descriptions.add(Map.of("type", "card", "name", card.getName(),
                        "zone", card.getZone().getZoneType().name()));
            } else if (entity instanceof Player candidate && isBoundPlayer(candidate, observer)) {
                descriptions.add(Map.of("type", "player", "name", candidate.getName()));
            } else { throw new UnsupportedOperationException("selection entity"); }
            bindings.add(entity);
        }
        if (new HashSet<>(bindings).size() != bindings.size()) { throw new UnsupportedOperationException("duplicate native options"); }
        // Native InputSelectEntitiesFromList also caps both bounds to the available list size.
        int lower = Math.min(min, bindings.size());
        int upper = Math.min(max, bindings.size());
        Map<String, Object> facts = reader.snapshot();
        for (int i = 0; i < bindings.size(); i++) {
            Map<String, Object> key = reader.reference(bindings.get(i));
            String group = bindings.get(i) instanceof Card ? "cards" : "players";
            boolean observed = key != null && ((List<?>) facts.get(group)).stream()
                    .anyMatch(node -> Objects.equals(((Map<?, ?>) node).get("id"), key.get("id")));
            if (!observed) { throw new UnsupportedOperationException("Selection option is absent from observation"); }
            Map<String, Object> option = new java.util.LinkedHashMap<>(descriptions.get(i));
            option.put("key", key);
            descriptions.set(i, Map.copyOf(option));
        }
        Map<String, Object> decision = StateReader.data("kind", kind, "prompt", Objects.toString(title, ""),
                "items", List.copyOf(descriptions), "min", lower, "max", upper, "optional", optional,
                "relatedPlayer", related == null ? null : reader.reference(related));
        return new Prepared<>(kind, Map.of("state", facts, "question", decision), List.copyOf(bindings), lower, upper,
                optional);
    }

    @Override public String kind() { return kind; }

    @Override public Map<String, Object> encodeQuestion() { return data; }

    @SuppressWarnings("unchecked")
    @Override public List<Integer> encodeAnswer(Object answer) {
        if (answer instanceof List<?> list) {
            if (!list.isEmpty() && list.get(0) instanceof Integer) {
                validate((List<Integer>) list);
                return (List<Integer>) list;
            }
            // List of native entities
            List<Integer> result = new ArrayList<>();
            for (Object entity : list) {
                int index = bindings.indexOf(entity);
                if (index < 0 || bindings.get(index) != entity) {
                    throw new IllegalArgumentException("Chosen entity is not a legal option");
                }
                result.add(index);
            }
            validate(result);
            return List.copyOf(result);
        }
        // Fallback for single entity chosen by the original controller
        if (answer == null) {
            List<Integer> result = List.of();
            validate(result);
            return result;
        }
        int index = bindings.indexOf(answer);
        if (index < 0 || bindings.get(index) != answer) {
            throw new IllegalArgumentException("Chosen entity is not a legal option");
        }
        List<Integer> result = List.of(index);
        validate(result);
        return result;
    }

    void validate(List<Integer> selected) {
        Objects.requireNonNull(selected, "Missing selection response");
        for (Object value : selected) {
            if (!(value instanceof Integer)) { throw new IllegalArgumentException("Selection must contain integers"); }
        }
        if (hasInvalidSelection(selected)) {
            throw new IllegalArgumentException("Invalid selection response");
        }
    }

    private static boolean hasUnsupportedMetadata(DelayedReveal reveal, Map<String, Object> params) {
        return reveal != null || params != null && !params.isEmpty();
    }

    private static boolean hasForeignRelatedPlayer(Player observer, Player related) {
        return related != null && !isBoundPlayer(related, observer);
    }

    private static boolean isHiddenOrUnboundCard(Card card, Player observer) {
        return card.getGame() != observer.getGame() || card.getZone() == null || card.isFaceDown()
                || card.getZone().getCards().stream().noneMatch(current -> current == card)
                || !card.getView().canBeShownTo(observer.getView());
    }

    private static boolean isBoundPlayer(Player candidate, Player observer) {
        return candidate.getGame() == observer.getGame()
                && observer.getGame().getPlayers().stream().anyMatch(current -> current == candidate);
    }

    private boolean hasInvalidSelection(List<Integer> selected) {
        return selected.size() > upper || selected.size() < lower && !(optional && selected.isEmpty())
                || new HashSet<>(selected).size() != selected.size()
                || selected.stream().anyMatch(index -> index < 0 || index >= bindings.size());
    }

    private record Prepared<T extends GameEntity>(String kind, Map<String, Object> data, List<T> bindings, int lower, int upper,
                                                   boolean optional) { }
}
