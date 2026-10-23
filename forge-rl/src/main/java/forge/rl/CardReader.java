package forge.rl;

import forge.StaticData;
import forge.card.CardStateName;
import forge.card.CardTypeView;
import forge.card.ColorSet;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardState;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordInterface;
import forge.game.staticability.StaticAbilityMustAttack;
import forge.game.staticability.StaticAbilityMustBlock;
import forge.game.zone.ZoneType;

import java.util.*;
import com.google.common.base.Supplier;

import static forge.rl.ObservationFacade.data;

/** Reads current and printed card facts after the facade authorizes access. */
final class CardReader {
    private CardReader() { }

    static Map<String, Object> read(Card card, ObservationFacade view, Combat combat) {
        ZoneType zone = card.getZone() == null ? null : card.getZone().getZoneType();
        boolean battlefield = zone == ZoneType.Battlefield;
        boolean down = card.isFaceDown();
        CardState identity = view.identity(card);
        boolean characteristics = (identity != null || down) && (!down || battlefield || zone == ZoneType.Stack);
        boolean creature = characteristics && card.isCreature();
        List<Object> doors = null;
        if (identity != null && battlefield && !down && card.isRoom()) {
            identity = card.getState(CardStateName.Original);
            doors = List.of(
                    List.of(card.getState(CardStateName.LeftSplit).getName(), card.getUnlockedRooms().contains(CardStateName.LeftSplit)),
                    List.of(card.getState(CardStateName.RightSplit).getName(), card.getUnlockedRooms().contains(CardStateName.RightSplit)));
        }
        String name = identity == null ? null : identity.getName();
        Boolean faceDown = battlefield || zone == ZoneType.Stack || zone == ZoneType.Exile ? down : null;
        var result = data("id", view.alias(card.getId()), "card_name", name,
                "scope", zone == ZoneType.Hand || zone == ZoneType.Library || down && name != null ? "observer_private" : "public",
                "zone", zone == null ? "unknown" : zone.name().toLowerCase(Locale.ROOT),
                "owner", view.side(card.getOwner()), "controller", view.side(card.getController()),
                "is_token", card.isToken(), "is_commander", card.isCommander(), "is_face_down", faceDown,
                "tapped", battlefield ? card.isTapped() : null,
                "power", creature ? card.getNetPower() : null, "toughness", creature ? card.getNetToughness() : null,
                "base_power", down || identity == null ? null : PrintedText.stat(identity.getBasePowerString()),
                "base_toughness", down || identity == null ? null : PrintedText.stat(identity.getBaseToughnessString()),
                "base_loyalty", down || identity == null ? null : PrintedText.stat(identity.getBaseLoyalty()),
                "damage", battlefield ? card.getDamage() : null, "counters", battlefield ? counters(card) : null,
                "is_attacking", battlefield ? combat != null && combat.isAttacking(card) : null,
                "is_blocked", battlefield && creature ? combat != null && combat.isBlocked(card) : null,
                "is_blocking", battlefield && creature ? combat != null && combat.isBlocking(card) : null,
                "dealt_first_strike_damage", battlefield && creature && combat != null ? dealtFirstStrike(combat, card) : null,
                "cant_attack", battlefield && creature ? !CombatUtil.canAttack(card) : null,
                "cant_block", battlefield && creature ? !CombatUtil.canBlock(card) : null,
                "must_attack", battlefield && creature ? !StaticAbilityMustAttack.entitiesMustAttack(card).isEmpty() || card.isGoaded() : null,
                "must_block", battlefield && creature ? mustBlock(card, combat) : null,
                "summoning_sick", battlefield ? card.hasSickness() : null,
                "oracle_text", PrintedText.oracle(identity), "copied_name", identity == null ? null : PrintedText.copiedName(card),
                "doors", doors, "status", characteristics ? status(card) : List.of(),
                "designations", characteristics ? designations(card) : List.of(),
                "class_level", characteristics && card.getClassLevel() > 1 ? card.getClassLevel() : null,
                "chosen_colors", identity == null ? List.of() : strings(card.getView().getChosenColors(), true),
                "chosen_type", identity == null ? null : chosenType(card),
                "chosen_name", identity == null || card.getView().getNamedCard() == null ? null : emptyToNull(String.join(", ", card.getView().getNamedCard())),
                "has_chosen_player", identity != null && card.getView().getChosenPlayer() != null,
                "chosen_mode", identity == null ? null : PrintedText.clean(card.getView().getChosenMode()),
                "optional_costs_paid", identity == null ? List.of() : optionalCosts(card),
                "ability_activations_this_turn", characteristics ? card.getAbilityActivatedThisTurn().values().stream().mapToInt(Collection::size).sum() : 0,
                "planeswalker_ability_activations_this_turn", characteristics ? card.getPlaneswalkerAbilityActivated() : 0,
                "regeneration_shields", battlefield ? card.getShieldCount() : 0,
                "copiable", characteristics ? copiable(card.getCurrentState()) : null);
        result.putAll(characteristics ? features(card) : data("mana_value", null, "card_types", List.of(), "subtypes", List.of(), "colors", List.of(), "keywords", List.of()));
        return result;
    }

    static Map<String, Object> features(Card card) {
        return data("mana_value", card.getCMC(), "card_types", types(card.getType()),
                "subtypes", strings(card.getType().getSubtypes(), false), "colors", colors(card.getColor()),
                "keywords", keywords(card.getUnhiddenKeywords()));
    }

    private static Map<String, Object> copiable(CardState state) {
        boolean stats = state.getType().isCreature() || state.hasPrintedPT();
        return data("power", stats && (state.getBasePowerString() == null || PrintedText.stat(state.getBasePowerString()) != null) ? state.getBasePower() : null,
                "toughness", stats && (state.getBaseToughnessString() == null || PrintedText.stat(state.getBaseToughnessString()) != null) ? state.getBaseToughness() : null,
                "loyalty", PrintedText.stat(state.getBaseLoyalty()), "mana_value", state.getManaCost().getCMC(),
                "card_types", types(state.getType()), "subtypes", strings(state.getType().getSubtypes(), false),
                "colors", colors(state.getColor()), "keywords", keywords(state.getIntrinsicKeywords()));
    }

    static Map<String, Object> printed(String name) {
        var paper = StaticData.instance().getCommonCards().getCard(name);
        if (paper == null) { return Map.of(); }
        var rules = paper.getRules();
        return data("mana_value", rules.getManaCost().getCMC(), "card_types", types(rules.getType()),
                "subtypes", strings(rules.getType().getSubtypes(), false), "colors", colors(rules.getColor()),
                "oracle_text", PrintedText.clean(rules.getOracleText()),
                "base_power", PrintedText.stat(rules.getPower()), "base_toughness", PrintedText.stat(rules.getToughness()),
                "base_loyalty", PrintedText.stat(rules.getInitialLoyalty()));
    }

    static List<Object> counters(GameEntity entity) {
        List<Object> result = new ArrayList<>();
        entity.getCounters().entrySet().forEach(entry -> {
            String name = entry.getElement().getName().toLowerCase(Locale.ROOT);
            result.add(List.of(name.equals("loyal") ? "loyalty" : name, entry.getCount()));
        });
        result.sort(Comparator.comparing(value -> (String) ((List<?>) value).get(0)));
        return result;
    }

    private static List<String> types(CardTypeView type) {
        List<String> result = new ArrayList<>();
        if (type.isArtifact()) { result.add("artifact"); }
        if (type.isBasic()) { result.add("basic"); }
        if (type.isBattle()) { result.add("battle"); }
        if (type.isCreature()) { result.add("creature"); }
        if (type.isEnchantment()) { result.add("enchantment"); }
        if (type.isInstant()) { result.add("instant"); }
        if (type.isLand()) { result.add("land"); }
        if (type.isLegendary()) { result.add("legendary"); }
        if (type.isPlaneswalker()) { result.add("planeswalker"); }
        if (type.isSnow()) { result.add("snow"); }
        if (type.isSorcery()) { result.add("sorcery"); }
        return result;
    }

    private static List<String> colors(ColorSet colors) {
        List<String> result = new ArrayList<>();
        if (colors.hasWhite()) { result.add("white"); }
        if (colors.hasBlue()) { result.add("blue"); }
        if (colors.hasBlack()) { result.add("black"); }
        if (colors.hasRed()) { result.add("red"); }
        if (colors.hasGreen()) { result.add("green"); }
        if (colors.isColorless()) { result.add("colorless"); }
        return result;
    }

    private static List<List<String>> keywords(Iterable<KeywordInterface> keywords) {
        List<List<String>> result = new ArrayList<>();
        for (var entry : keywords) {
            if (entry.getKeyword() == Keyword.UNDEFINED) { continue; }
            String name = PrintedText.clean(entry.getKeyword().toString()).toLowerCase(Locale.ROOT);
            String title = PrintedText.clean(entry.getTitle());
            String parameter = title.toLowerCase(Locale.ROOT).startsWith(name)
                    ? title.substring(name.length()).replaceAll("^[ —-]+|[ —-]+$", "") : "";
            if (!parameter.contains("{")) { parameter = parameter.toLowerCase(Locale.ROOT); }
            result.add(List.of(name, parameter));
        }
        result.sort(Comparator.comparing((List<String> value) -> value.get(0)).thenComparing(value -> value.get(1)));
        return result;
    }

    private static List<String> status(Card card) {
        List<String> result = new ArrayList<>();
        switch (card.getCurrentStateName()) {
            case Flipped -> result.add("flipped");
            case Backside -> { result.add("back_face_up"); result.add(card.isModal() ? "modal" : "transformed"); }
            case Meld -> result.add("melded");
            default -> { }
        }
        if (card.isPhasedOut()) { result.add("phased_out"); }
        Collections.sort(result);
        return result;
    }

    private static List<String> designations(Card card) {
        Set<String> result = new TreeSet<>();
        if (card.isMonstrous()) { result.add("monstrous"); }
        if (card.isRenowned()) { result.add("renowned"); }
        if (card.isSuspected()) { result.add("suspected"); }
        if (card.isGoaded()) { result.add("goaded"); }
        if (card.isRingBearer()) { result.add("ring-bearer"); }
        if (card.isSolved()) { result.add("solved"); }
        if (card.isSaddled()) { result.add("saddled"); }
        if (card.getTimesCrewedThisTurn() > 0) { result.add("crewed"); }
        if (card.getTimesSaddledThisTurn() > 0) { result.add("saddled_this_turn"); }
        return new ArrayList<>(result);
    }

    private static String chosenType(Card card) {
        return emptyToNull(String.join(", ", java.util.stream.Stream.of(card.getView().getChosenType(), card.getView().getChosenType2())
                .filter(value -> value != null && !value.isEmpty()).map(PrintedText::clean).toList()));
    }

    private static List<String> optionalCosts(Card card) {
        Set<String> result = new TreeSet<>();
        if (card.getKickerMagnitude() > 0) { result.add("kicked"); }
        var cast = card.getCastSA();
        if (cast != null) {
            if (cast.isGiftPromised()) { result.add("gift_promised"); }
            if (cast.isBargained()) { result.add("bargained"); }
            if (cast.getOptionalKeywords().contains(Keyword.OFFSPRING)) { result.add("offspring"); }
        }
        if (card.hasPromisedGift()) { result.add("gift_promised"); }
        return new ArrayList<>(result);
    }

    private static boolean mustBlock(Card card, Combat combat) {
        if (combat != null) { return CombatUtil.mustBlockAnAttacker(card, combat, null); }
        return !card.getMustBlockCards().isEmpty() || StaticAbilityMustBlock.blocksEachCombatIfAble(card);
    }

    private static boolean dealtFirstStrike(Combat combat, Card card) {
        try {
            // Preserve the existing read-only observation of this baseline's private fact.
            var field = Combat.class.getDeclaredField("combatantsThatDealtFirstStrikeDamage");
            field.setAccessible(true);
            Object collection = ((Supplier<?>) field.get(combat)).get();
            return ((Collection<?>) collection).contains(card);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot read native first-strike history", error);
        }
    }

    static List<String> strings(Iterable<?> values, boolean lower) {
        List<String> result = new ArrayList<>();
        if (values != null) { values.forEach(value -> result.add(lower ? value.toString().toLowerCase(Locale.ROOT) : value.toString())); }
        Collections.sort(result);
        return result;
    }

    private static String emptyToNull(String value) { return value.isEmpty() ? null : value; }
}
