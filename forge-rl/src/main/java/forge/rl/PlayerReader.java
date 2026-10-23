package forge.rl;

import com.google.common.collect.Iterables;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import java.util.*;

import static forge.rl.ObservationFacade.data;

/** Public player counters and native mana-source capabilities. */
final class PlayerReader {
    private PlayerReader() { }

    static Map<String, Object> read(Player player, ObservationFacade view, int seat) {
        Game game = player.getGame();
        var pool = player.getManaPool();
        var result = data("id", player.getId(), "side", view.side(player), "life", player.getLife(),
                "starting_life", player.getStartingLife(), "seat", seat, "poison", player.getPoisonCounters(),
                "mana_white", pool.getAmountOfColor((byte) 1), "mana_blue", pool.getAmountOfColor((byte) 2),
                "mana_black", pool.getAmountOfColor((byte) 4), "mana_red", pool.getAmountOfColor((byte) 8),
                "mana_green", pool.getAmountOfColor((byte) 16), "mana_colorless", pool.getAmountOfColor((byte) 0),
                "counters", player.getCounters().isEmpty() ? null : CardReader.counters(player),
                "mulligans_taken", player.getStats().getMulliganCount(), "is_monarch", player.isMonarch(),
                "has_initiative", player.hasInitiative(), "started_game", player == game.getStartingPlayer(),
                "speed", player.getSpeed(), "speed_rose_this_turn", null, "ring_tempts", player.getNumRingTemptedYou(),
                "completed_dungeons", player.getCompletedDungeons().size(), "current_dungeon_room", room(player),
                "current_dungeon_room_level", null, "has_city_blessing", player.hasBlessing(),
                "maximum_hand_size", player.getMaxHandSize(), "unlimited_hand_size", player.isUnlimitedHandSize(),
                "lands_played_this_turn", player.getLandsPlayedThisTurn(),
                "lands_allowed_this_turn", player.getMaxLandPlaysInfinite() ? null : player.getMaxLandPlays(),
                "commander_casts", player.getCommanders().stream().mapToInt(player::getCommanderCast).sum(),
                "statistics", statistics(player));
        Map<String, Integer> available = new TreeMap<>();
        String[] names = {"white", "blue", "black", "red", "green", "colorless"};
        String[] symbols = {"W", "U", "B", "R", "G", "C"};
        for (String name : names) { available.put(name, 0); }
        int total = 0;
        for (Card card : player.getCardsIn(ZoneType.Battlefield)) {
            if (card.isTapped()) { continue; }
            var abilities = card.getManaAbilities().stream()
                    .filter(ability -> !(card.isAbilitySick() && ability.getPayCosts().hasTapCost())).toList();
            if (abilities.isEmpty()) { continue; }
            Set<String> produced = new HashSet<>();
            for (int i = 0; i < names.length; i++) {
                String symbol = symbols[i];
                if (abilities.stream().anyMatch(ability -> ability.canProduce(symbol))) { produced.add(names[i]); }
            }
            if (produced.isEmpty()) { produced.add("colorless"); }
            total++;
            produced.forEach(color -> available.merge(color, 1, Integer::sum));
        }
        result.put("available_mana", available.entrySet().stream().map(entry -> List.of(entry.getKey(), entry.getValue())).toList());
        result.put("available_mana_total", total);
        return result;
    }

    private static String room(Player player) {
        for (Card card : player.getCardsIn(ZoneType.Command)) {
            if (card.getType().isDungeon() && !card.getCurrentRoom().isEmpty()) { return card.getCurrentRoom(); }
        }
        return null;
    }

    private static List<List<Object>> statistics(Player player) {
        Game game = player.getGame();
        var entered = player.getZone(ZoneType.Battlefield).getCardsAddedThisTurn(null);
        Map<String, Integer> values = new TreeMap<>();
        values.put("attacked_creatures", player.getCreaturesAttackedThisTurn().size());
        values.put("attacked_players", Iterables.size(player.getAttackedPlayersMyTurn()));
        values.put("attacked_players_last_turn", player.getAttackedPlayersMyLastTurn().size());
        values.put("cards_discarded", player.getDiscardedThisTurn().size());
        values.put("cards_drawn", player.getNumDrawnThisTurn());
        values.put("cards_drawn_last_turn", player.getNumDrawnLastTurn());
        values.put("combat_damage_dealt_to_players", damage(player, true));
        values.put("counters_added", game.getCounterAddedThisTurn(null, "You", "Card", null, player, null));
        values.put("creatures_died", (int) player.getZone(ZoneType.Graveyard).getCardsAddedThisTurn(ZoneType.Battlefield).stream().filter(Card::isCreature).count());
        values.put("crimes_committed", player.getCommittedCrimeThisTurn());
        values.put("damage_dealt_to_players", damage(player, null));
        values.put("descended", player.getDescended());
        values.put("explored", player.getNumExploredThisTurn());
        values.put("foretold", player.getNumForetoldThisTurn());
        values.put("investigated", player.getInvestigateNumThisTurn());
        values.put("lands_entered", (int) entered.stream().filter(Card::isLand).count());
        values.put("lands_played", player.getLandsPlayedThisTurn());
        values.put("lands_played_last_turn", player.getLandsPlayedLastTurn());
        values.put("life_gained", player.getLifeGainedThisTurn());
        values.put("life_gained_times", player.getLifeGainedTimesThisTurn());
        values.put("life_lost", player.getLifeLostThisTurn());
        values.put("life_lost_last_turn", player.getLifeLostLastTurn());
        values.put("mana_spent_on_spells", player.getExpentThisTurn());
        values.put("permanents_entered", entered.size());
        values.put("permanents_left_battlefield", (int) game.getLeftBattlefieldThisTurn().stream().filter(card -> card.getController() == player).count());
        values.put("permanents_sacrificed", player.getSacrificedThisTurn().size());
        values.put("spells_cast", player.getSpellsCastThisTurn());
        values.put("spells_cast_last_turn", player.getSpellsCastLastTurn());
        values.put("spells_cast_this_game", player.getSpellsCastThisGame());
        values.put("surveilled", player.getSurveilThisTurn());
        values.put("tokens_created", player.getNumTokenCreatedThisTurn());
        values.put("ventured", player.getVenturedThisTurn());
        return values.entrySet().stream().map(entry -> List.<Object>of(entry.getKey(), entry.getValue())).toList();
    }

    private static int damage(Player player, Boolean combat) {
        return player.getGame().getDamageDoneThisTurn(combat, false, "Card.YouCtrl", "Player", null, player, null)
                .stream().mapToInt(Integer::intValue).sum();
    }
}
