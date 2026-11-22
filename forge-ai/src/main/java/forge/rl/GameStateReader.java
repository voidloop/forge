package forge.rl;

import com.google.common.collect.Multiset;
import forge.card.MagicColor;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.combat.Combat;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.TargetChoices;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Detached current observations for one player in an ordinary duel.
 * Call snapshot only at a stable game boundary on the game-owning thread.
 * This reader does not evaluate choices, advance the game or maintain memory.
 * IDs belong to one snapshot; they do not track hidden cards through shuffles.
 * Complete history, remembered reveals and pending delayed effects are absent.
 */
public final class GameStateReader {
    private static final ZoneType[] ZONES = {ZoneType.Hand, ZoneType.Library,
            ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile,
            ZoneType.Command, ZoneType.Stack, ZoneType.Sideboard};
    private final Game game;
    private final Player observer;
    private final Map<Player, String> playerIds = new IdentityHashMap<>();
    private final Map<Card, String> cardIds = new IdentityHashMap<>();
    private final Map<Card, String> knownCardIds = new IdentityHashMap<>();
    private final Map<SpellAbilityStackInstance, String> stackIds = new IdentityHashMap<>();

    public GameStateReader(final Game game, final Player observer) {
        this.game = Objects.requireNonNull(game, "game");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    public Map<String, Object> snapshot() {
        resetSnapshotReferences();

        List<Card> visibleCards = new ArrayList<>();
        List<Map<String, Object>> zones = new ArrayList<>();
        for (Player player : game.getRegisteredPlayers()) {
            collectZones(player, zones, visibleCards);
        }

        // Stack targets can refer to other entries, so assign all IDs before reading them.
        int stackIndex = 0;
        for (SpellAbilityStackInstance item : game.getStack()) {
            stackIds.put(item, "s" + stackIndex++);
        }

        List<Map<String, Object>> cards = new ArrayList<>();
        for (Card card : visibleCards) {
            cards.add(readCard(card));
        }

        List<Map<String, Object>> players = new ArrayList<>();
        for (Player player : game.getRegisteredPlayers()) {
            players.add(readPlayer(player));
        }
        return data("version", 1, "scope", "current_observation", "id_scope", "snapshot",
                "observer", playerIds.get(observer), "game", readGame(), "players", players,
                "zones", zones, "cards", cards, "stack", readStack(), "combat", readCombat(),
                "history", readHistory(), "effects", data("representation", "current_characteristics"),
                "coverage", data("remembered_cards", false, "complete_history", false,
                        "pending_delayed_effects", false, "effect_durations", false));
    }

    /** Read once the native game loop returns, using the original registration order for seats. */
    public static Map<String, Object> terminalOutcome(final Game game) {
        var outcome = game.getOutcome();
        if (!game.isGameOver() || outcome == null) {
            throw new IllegalStateException("Native game returned without a terminal outcome");
        }
        var players = game.getRegisteredPlayers();
        List<Integer> winners = new ArrayList<>();
        for (int seat = 0; seat < players.size(); seat++) {
            if (outcome.isWinner(players.get(seat).getRegisteredPlayer())) {
                winners.add(seat);
            }
        }
        return data("draw", outcome.isDraw(), "winner_seats", winners,
                "reason", outcome.getWinCondition().toString(), "turns", outcome.getLastTurnNumber(),
                "details", outcome.getOutcomeStrings());
    }

    private void resetSnapshotReferences() {
        // Controllers are constructed before Forge finishes registering players.
        playerIds.clear();
        if (game.getRegisteredPlayers().size() != 2) {
            throw new IllegalArgumentException("State reader requires an ordinary duel");
        }
        int seat = 0;
        for (Player player : game.getRegisteredPlayers()) {
            playerIds.put(player, "p" + seat++);
        }
        if (observer.getGame() != game || !playerIds.containsKey(observer)) {
            throw new IllegalArgumentException("Observer must belong to this game");
        }
        cardIds.clear();
        knownCardIds.clear();
        stackIds.clear();
    }

    /** Returns the reference only if the card's identity was known before selection. */
    String knownCardReference(final Card card) {
        return knownCardIds.get(card);
    }

    private Map<String, Object> readGame() {
        var phaseHandler = game.getPhaseHandler();
        int turn = phaseHandler.getTurn();
        var phase = phaseHandler.getPhase();
        return data("turn", turn == 0 ? null : turn,
                "phase", phase == null ? null : phase.name(),
                "active_player", playerIds.get(phaseHandler.getPlayerTurn()),
                "priority_player", playerIds.get(phaseHandler.getPriorityPlayer()),
                "day_night", readDayNight(), "outcome", readOutcome());
    }

    private String readOutcome() {
        if (!game.isGameOver() || game.getOutcome() == null) {
            return "ongoing";
        }
        if (game.getOutcome().isDraw()) {
            return "draw";
        }
        return game.getOutcome().isWinner(observer.getRegisteredPlayer()) ? "win" : "loss";
    }

    private String readDayNight() {
        if (game.isDay()) {
            return "day";
        }
        return game.isNight() ? "night" : "neither";
    }

    private Map<String, Object> readPlayer(final Player player) {
        Map<String, Object> mana = new LinkedHashMap<>();
        for (MagicColor.Color color : MagicColor.Color.values()) {
            mana.put(color.name().toLowerCase(Locale.ROOT), player.getManaPool().getAmountOfColor(color.getColorMask()));
        }
        return data("id", playerIds.get(player), "seat", game.getRegisteredPlayers().indexOf(player),
                "life", player.getLife(), "counters", counters(player), "mana_pool", mana,
                "max_hand_size", player.isUnlimitedHandSize() ? null : player.getMaxHandSize(),
                "unlimited_hand_size", player.isUnlimitedHandSize(),
                "lands_played_this_turn", player.getLandsPlayedThisTurn(),
                "max_land_plays", player.getMaxLandPlaysInfinite() ? null : player.getMaxLandPlays(),
                "unlimited_land_plays", player.getMaxLandPlaysInfinite(),
                "spells_cast_this_turn", player.getSpellsCastThisTurn());
    }

    private void collectZones(final Player player, final List<Map<String, Object>> zones,
                              final List<Card> visibleCards) {
        for (ZoneType zone : ZONES) {
            List<Card> shown = new ArrayList<>();
            int count = 0;
            for (Card card : player.getCardsIn(zone, false)) {
                count++;
                if (canObserve(card)) {
                    shown.add(card);
                }
            }
            // A visibility grant does not by itself authorize library positions.
            if (zone == ZoneType.Library) {
                shown.sort(Comparator.comparingInt(Card::getId));
            }
            List<String> references = new ArrayList<>();
            for (Card card : shown) {
                if (!cardIds.containsKey(card)) {
                    cardIds.put(card, "c" + cardIds.size());
                    visibleCards.add(card);
                }
                references.add(cardIds.get(card));
            }
            zones.add(data("owner", playerIds.get(player), "zone", zone.name().toLowerCase(Locale.ROOT),
                    "count", count, "unknown_count", count - shown.size(), "cards", references));
        }
    }

    private boolean canObserve(final Card card) {
        return card.getView().canBeShownTo(observer.getView());
    }

    private boolean knowsIdentity(final Card card) {
        return canObserve(card) && card.getView().canFaceDownBeShownTo(observer.getView());
    }

    private Map<String, Object> readCard(final Card card) {
        boolean known = knowsIdentity(card);
        if (known) {
            knownCardIds.put(card, cardIds.get(card));
        }
        CardView.CardStateView current = card.getView().getCurrentState();
        Map<String, Object> knownFace = readKnownFace(card, known);
        String name = known ? current.getName() : null;
        if (knownFace != null) {
            name = (String) knownFace.get("name");
        }
        List<String> types = new ArrayList<>();
        card.getType().getSupertypes().forEach(type -> types.add(type.toString()));
        card.getType().getCoreTypes().forEach(type -> types.add(type.toString()));
        card.getType().getSubtypes().forEach(types::add);
        List<String> colors = new ArrayList<>();
        for (MagicColor.Color color : MagicColor.Color.values()) {
            if (color.getColorMask() != MagicColor.COLORLESS && card.getColor().hasAnyColor(color.getColorMask())) {
                colors.add(color.name().toLowerCase(Locale.ROOT));
            }
        }
        List<String> keywords = new ArrayList<>();
        card.getKeywords().forEach(keyword -> keywords.add(keyword.getOriginal()));
        ZoneType zone = card.getZone().getZoneType();
        boolean onBattlefield = zone == ZoneType.Battlefield;
        Player controller = onBattlefield || zone == ZoneType.Stack ? card.getController() : null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", cardIds.get(card));
        result.put("zone", zone.name().toLowerCase(Locale.ROOT));
        result.put("owner", playerIds.get(card.getOwner()));
        result.put("controller", playerIds.get(controller));
        result.put("name", name);
        result.put("face_down", card.isFaceDown());
        result.put("identity_known", known);
        result.put("known_face", knownFace);
        result.put("types", types);
        result.put("colors", colors);
        result.put("mana_cost", known && !current.getManaCost().isNoCost() ? current.getManaCost().toString() : null);
        result.put("mana_value", card.getCMC());
        result.put("power", card.isCreature() ? card.getNetPower() : null);
        result.put("toughness", card.isCreature() ? card.getNetToughness() : null);
        result.put("tapped", onBattlefield ? card.isTapped() : null);
        result.put("phased_out", onBattlefield ? card.isPhasedOut() : null);
        result.put("damage", onBattlefield ? card.getDamage() : null);
        result.put("counters", counters(card));
        result.put("summoning_sick", onBattlefield ? card.isSick() : null);
        result.put("keywords", keywords);
        result.put("rules_text", known ? current.getAbilityText() : null);

        Object oracleText = null;
        if (known) {
            oracleText = knownFace == null ? current.getOracleText() : knownFace.get("oracle_text");
        }
        result.put("oracle_text", oracleText);
        result.put("attached_to", entityReference(attachedEntity(card)));
        return result;
    }

    private Map<String, Object> readKnownFace(final Card card, final boolean known) {
        if (!card.isFaceDown() || !known) {
            return null;
        }
        CardView.CardStateView face = card.getState(card.getFaceupCardStateName()).getView();
        return data("name", face.getName(),
                "mana_cost", face.getManaCost().isNoCost() ? null : face.getManaCost().toString(),
                "rules_text", face.getAbilityText(), "oracle_text", face.getOracleText());
    }

    private GameEntity attachedEntity(final Card card) {
        return card.getAttachedTo() != null ? card.getAttachedTo() : card.getPlayerAttachedTo();
    }

    private List<Map<String, Object>> readStack() {
        List<Map<String, Object>> result = new ArrayList<>();
        int position = 0;
        for (SpellAbilityStackInstance item : game.getStack()) {
            List<Map<String, Object>> targets = new ArrayList<>();
            for (SpellAbilityStackInstance part = item; part != null; part = part.getSubInstance()) {
                addTargets(part.getTargetChoices(), targets);
            }
            Card source = item.getSourceCard();
            result.add(data("id", stackIds.get(item), "position", position++,
                    "source", cardIds.get(source), "controller", playerIds.get(item.getActivatingPlayer()),
                    "kind", stackKind(item),
                    "description", knownCardIds.containsKey(source) ? item.getSpellAbility().getDescription() : null,
                    "targets", targets));
        }
        return result;
    }

    private String stackKind(final SpellAbilityStackInstance item) {
        if (item.isSpell()) {
            return "spell";
        }
        return item.isTrigger() ? "triggered_ability" : "activated_ability";
    }

    private void addTargets(final TargetChoices choices, final List<Map<String, Object>> result) {
        for (Card target : choices.getTargetCards()) {
            result.add(data("kind", "card", "reference", cardIds.get(target)));
        }
        for (Player target : choices.getTargetPlayers()) {
            result.add(data("kind", "player", "reference", playerIds.get(target)));
        }
        for (SpellAbility target : choices.getTargetSpells()) {
            SpellAbilityStackInstance item = game.getStack().getInstanceMatchingSpellAbilityID(target);
            result.add(data("kind", "stack", "reference", item == null ? null : stackIds.get(item)));
        }
    }

    private List<Map<String, Object>> readCombat() {
        List<Map<String, Object>> result = new ArrayList<>();
        Combat combat = game.getCombat();
        if (combat == null) {
            return result;
        }
        for (Card attacker : combat.getAttackers()) {
            List<String> blockers = new ArrayList<>();
            for (Card blocker : combat.getBlockers(attacker)) {
                blockers.add(cardIds.get(blocker));
            }
            result.add(data("attacker", cardIds.get(attacker),
                    "defender", entityReference(combat.getDefenderByAttacker(attacker)),
                    "blocked", combat.isBlocked(attacker), "blockers", blockers));
        }
        return result;
    }

    private Map<String, Object> readHistory() {
        List<Map<String, Object>> players = new ArrayList<>();
        for (Player player : game.getRegisteredPlayers()) {
            players.add(data("player", playerIds.get(player), "cards_drawn", player.getNumDrawnThisTurn(),
                    "life_gained", player.getLifeGainedThisTurn(), "life_lost", player.getLifeLostThisTurn(),
                    "lands_played", player.getLandsPlayedThisTurn(), "spells_cast", player.getSpellsCastThisTurn()));
        }
        return data("scope", "native_turn_counters", "players", players);
    }

    private String entityReference(final GameEntity entity) {
        if (entity instanceof Player) {
            return playerIds.get(entity);
        }
        if (entity instanceof Card) {
            return cardIds.get(entity);
        }
        return null;
    }

    private Map<String, Object> counters(final GameEntity entity) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Multiset.Entry<forge.game.card.CounterType> entry : entity.getCounters().entrySet()) {
            values.put(entry.getElement().toString(), entry.getCount());
        }
        return values;
    }

    private static Map<String, Object> data(final Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put((String) entries[index], entries[index + 1]);
        }
        return result;
    }
}
