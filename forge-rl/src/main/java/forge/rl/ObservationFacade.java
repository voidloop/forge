package forge.rl;

import forge.card.ColorSet;
import forge.card.ICardFace;
import forge.game.CardTraitBase;
import forge.game.card.Card;
import forge.game.card.CardState;
import forge.game.card.CardView;
import forge.game.card.CounterType;
import forge.game.cost.CostPart;
import forge.game.mana.Mana;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbility;
import forge.game.zone.PlayerZone;
import org.apache.commons.text.StringEscapeUtils;

import java.security.SecureRandom;
import java.util.*;
import java.util.stream.Collectors;

/** Observer-bound access to Forge. Only detached data crosses this boundary. */
public final class ObservationFacade {
    private final Player observer;
    private final Map<Integer, Integer> aliases = new HashMap<>();
    private final Set<Integer> usedAliases = new HashSet<>();
    private final Random random;
    private KnowledgeEventObserver events;

    public ObservationFacade(Player observer) {
        this(observer, new SecureRandom());
    }

    ObservationFacade(Player observer, Random random) {
        this.observer = Objects.requireNonNull(observer);
        this.random = random;
    }

    public void observe(IKnowledgeEventCallback callback) {
        if (events != null) { throw new IllegalStateException("Observer is already bound"); }
        events = new KnowledgeEventObserver(observer, this, callback);
        observer.getGame().subscribeToEvents(events);
    }

    public Map<String, Object> snapshot() {
        if (events != null) { events.flush(); }
        return ObservationReader.read(observer, this);
    }

    public Map<String, Object> printedCards(String[] names) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : names) { result.put(name, CardReader.printed(name)); }
        return result;
    }

    public int turn() { return observer.getGame().getPhaseHandler().getTurn(); }

    /** Called only after the game worker stops; release the Python callback. */
    public void close() {
        if (events != null) { events.close(); events = null; }
    }

    boolean canSee(CardView card) {
        return card != null && card.canBeShownTo(observer.getView());
    }

    boolean canIdentify(CardView card) {
        return canSee(card) && card.canFaceDownBeShownTo(observer.getView());
    }

    CardState identity(Card card) {
        if (card == null || !canIdentify(card.getView())) { return null; }
        return card.isFaceDown() ? card.getAlternateState() : card.getCurrentState();
    }

    String visibleName(CardView card) {
        if (!canIdentify(card)) { return null; }
        return disclosedName(card);
    }

    // An explicit native reveal authorizes face-up identities in hidden zones.
    String disclosedName(CardView card) {
        if (card == null) { return null; }
        if (!card.isFaceDown()) { return card.getOracleName(); }
        return card.canFaceDownBeShownTo(observer.getView()) && card.getAlternateState() != null
                ? card.getAlternateState().getOracleName() : null;
    }

    int alias(int nativeId) {
        return aliases.computeIfAbsent(nativeId, ignored -> {
            int value;
            do { value = random.nextInt(Integer.MAX_VALUE - 1) + 1; }
            while (!usedAliases.add(value));
            return value;
        });
    }

    void conceal(int nativeId) { aliases.remove(nativeId); }

    Map<String, Object> rememberedReference(int nativeId) {
        Integer id = aliases.get(nativeId);
        return id == null ? null : data("kind", "card", "id", id);
    }

    Integer referenceId(CardView card) { return canSee(card) ? alias(card.getId()) : null; }

    String side(Player player) {
        return player == null ? "none" : player == observer ? "self" : "opponent";
    }

    Map<String, Object> reference(Object entity) {
        if (entity instanceof Player player) { return reference(player.getView()); }
        if (entity instanceof PlayerView player) { return data("kind", "player", "id", player.getId()); }
        CardView card = entity instanceof Card value ? value.getView()
                : entity instanceof CardView value ? value : null;
        Integer id = referenceId(card);
        return id == null ? null : data("kind", "card", "id", id);
    }

    public Map<String, Object> decision(NativeDecision request) {
        if (request.getChooser() != observer && request.getChooser().getControllingPlayer() != observer) {
            throw new IllegalStateException("Decision routed to an unauthorized observer");
        }
        if (events != null) { events.flush(); }
        Map<String, Object> context = new LinkedHashMap<>();
        request.getContext().forEach((key, value) -> context.put(key, text(value)));
        return data("kind", request.getKind(), "prompt", request.getPrompt(),
                "options", request.getOptions().stream().map(this::option).toList(),
                "minimum", request.getMinimum(), "maximum", request.getMaximum(),
                "allow_empty", request.allowsEmpty(), "repeat", request.allowsRepeat(),
                "ordered", request.isOrdered(), "number", request.isNumber(), "context", context);
    }

    Map<String, Object> option(Object value) {
        if (value instanceof NativeDecision.CombatAssignment combat) {
            var creature = option(combat.creature());
            var target = option(combat.target());
            String operation = combat.remove() ? "remove" : "assign";
            return data("label", operation + ": " + creature.get("label") + " -> " + target.get("label"),
                    "object", creature.get("object"), "kind", "combat_assignment",
                    "target", target.get("object"), "operation", operation);
        }
        if (value instanceof Player player) { return option(player.getView()); }
        if (value instanceof PlayerView player) {
            return data("label", player.getName(), "object", reference(player), "kind", "player");
        }
        if (value instanceof Card card) { return option(card.getView()); }
        if (value instanceof CardView card) {
            String name = visibleName(card);
            return data("label", name == null ? "Hidden card" : name,
                    "object", reference(card), "kind", "card");
        }
        if (value instanceof SpellAbility ability) {
            CardState identity = identity(ability.getHostCard());
            if (identity == null) { return data("label", "Hidden action", "kind", "action"); }
            String text = PrintedText.ability(ability);
            return data("label", identity.getName() + ": " + (text == null ? "Action" : text),
                    "object", reference(ability.getHostCard()), "kind",
                    ability.isManaAbility() ? "mana_ability" : ability.isLandAbility() ? "land" : "action");
        }
        return data("label", text(value), "kind", "value");
    }

    String text(Object value) {
        if (value == null) { return "None"; }
        if (value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof Enum<?> || value instanceof ICardFace || value instanceof CostPart
                || value instanceof CounterType || value instanceof ColorSet || value instanceof Mana) {
            return String.valueOf(value);
        }
        if (value instanceof Card || value instanceof CardView || value instanceof Player
                || value instanceof PlayerView || value instanceof SpellAbility
                || value instanceof NativeDecision.CombatAssignment) {
            return (String) option(value).get("label");
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream().map(entry -> quote(text(entry.getKey())) + ": " + quote(text(entry.getValue())))
                    .collect(Collectors.joining(", ", "{", "}"));
        }
        if (value instanceof Collection<?> list) {
            return list.stream().map(item -> quote(text(item))).collect(Collectors.joining(", ", "[", "]"));
        }
        if (value instanceof PlayerZone zone) { return text(zone.getCards()); }
        if (value instanceof CardState state) {
            return identity(state.getCard()) == null ? "Hidden card state" : state.getName();
        }
        if (value instanceof CardTraitBase trait) {
            return identity(trait.getHostCard()) != null && trait.hasParam("Description")
                    ? trait.getParam("Description") : "Effect";
        }
        return "Option";
    }

    private static String quote(String value) { return "\"" + StringEscapeUtils.escapeJson(value) + "\""; }

    static Map<String, Object> data(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) { result.put((String) pairs[i], pairs[i + 1]); }
        return result;
    }
}
