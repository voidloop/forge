package forge.rl;

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
final class SelectionRequest<T extends GameEntity> {
    final Map<String, Object> data;
    private final List<T> bindings;
    private final int lower;
    private final int upper;
    private final boolean optional;

    SelectionRequest(Player observer, StateReader view, String kind, Iterable<T> options, String title, int min, int max,
            boolean optional, DelayedReveal reveal, Player related, Map<String, Object> params) {
        this.optional = optional;
        if (reveal != null || params != null && !params.isEmpty()) {
            throw new UnsupportedOperationException("selection reveal/parameters");
        }
        if (min < 0 || max < min) { throw new UnsupportedOperationException("selection bounds"); }
        if (related != null && (related.getGame() != observer.getGame()
                || !observer.getGame().getPlayers().contains(related))) {
            throw new UnsupportedOperationException("selection related player");
        }
        bindings = new ArrayList<>();
        List<Map<String, Object>> descriptions = new ArrayList<>();
        for (T entity : options) {
            if (entity instanceof Card card) {
                // shortcut: visible face-up cards only; add reveals/face-down choices after their own proof.
                if (card.getGame() != observer.getGame() || card.getZone() == null || card.isFaceDown()
                        || !card.getView().canBeShownTo(observer.getView())) {
                    throw new UnsupportedOperationException("selection of hidden or unbound card");
                }
                descriptions.add(Map.of("type", "card", "name", card.getName(),
                        "controller", card.getController().getName(), "zone", card.getZone().getZoneType().name()));
            } else if (entity instanceof Player candidate && candidate.getGame() == observer.getGame() && observer.getGame().getPlayers().contains(candidate)) {
                descriptions.add(Map.of("type", "player", "name", candidate.getName()));
            } else { throw new UnsupportedOperationException("selection entity"); }
            bindings.add(entity);
        }
        if (new HashSet<>(bindings).size() != bindings.size()) { throw new UnsupportedOperationException("duplicate native options"); }
        // Native InputSelectEntitiesFromList also caps both bounds to the available list size.
        lower = Math.min(min, bindings.size());
        upper = Math.min(max, bindings.size());
        Map<String, Object> facts = view.snapshot();
        for (int i = 0; i < bindings.size(); i++) {
            Map<String, Object> key = view.reference(bindings.get(i));
            String group = bindings.get(i) instanceof Card ? "cards" : "players";
            boolean observed = key != null && ((List<?>) facts.get(group)).stream()
                    .anyMatch(node -> Objects.equals(((Map<?, ?>) node).get("id"), key.get("id")));
            if (!observed) { throw new UnsupportedOperationException("Selection option is absent from observation"); }
            Map<String, Object> option = new java.util.LinkedHashMap<>(descriptions.get(i));
            option.put("key", key);
            descriptions.set(i, Map.copyOf(option));
        }
        Map<String, Object> decision = StateReader.data("kind", kind, "prompt", Objects.toString(title, ""),
                "options", List.copyOf(descriptions), "min", lower, "max", upper, "optional", optional,
                "relatedPlayer", related == null ? null : view.reference(related));
        data = Map.of("observation", facts, "decision", decision);
    }

    List<T> apply(List<Integer> selected) {
        Objects.requireNonNull(selected, "Missing selection response");
        for (Object value : selected) {
            if (!(value instanceof Integer)) { throw new IllegalArgumentException("Selection must contain integers"); }
        }
        if (selected.size() > upper || selected.size() < lower && !(optional && selected.isEmpty())
                || new HashSet<>(selected).size() != selected.size()
                || selected.stream().anyMatch(index -> index < 0 || index >= bindings.size())) {
            throw new IllegalArgumentException("Invalid selection response");
        }
        return selected.stream().map(bindings::get).toList();
    }

    List<Integer> encode(Iterable<T> selected) {
        Objects.requireNonNull(selected, "Missing native selection");
        List<Integer> indices = new ArrayList<>();
        for (T entity : selected) {
            int index = -1;
            for (int i = 0; i < bindings.size(); i++) {
                if (bindings.get(i) == entity) { index = i; break; }
            }
            if (index < 0) { throw new IllegalArgumentException("Native selection outside request bindings"); }
            indices.add(index);
        }
        apply(indices);
        return List.copyOf(indices);
    }
}
