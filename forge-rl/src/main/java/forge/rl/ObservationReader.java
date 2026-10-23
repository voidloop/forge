package forge.rl;

import forge.game.Game;
import forge.game.GameObject;
import forge.game.GameStage;
import forge.game.card.Card;
import forge.game.combat.CombatUtil;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.*;

import static forge.rl.ObservationFacade.data;

/** Reads detached native facts. Python owns memory and graph assembly. */
final class ObservationReader {
    private ObservationReader() { }

    static Map<String, Object> read(Player observer, ObservationFacade view) {
        Game game = observer.getGame();
        var phase = game.getPhaseHandler();
        List<Player> players = new ArrayList<>(game.getRegisteredPlayers());
        int start = players.indexOf(game.getStartingPlayer());
        List<Object> playerFacts = new ArrayList<>();
        List<Object> cardFacts = new ArrayList<>();
        List<Object> zones = new ArrayList<>();
        List<Object> known = new ArrayList<>();
        List<Object> commanderDamage = new ArrayList<>();
        Map<Integer, Long> timestamps = new HashMap<>();
        for (int i = 0; i < players.size(); i++) {
            Player player = players.get(i);
            playerFacts.add(PlayerReader.read(player, view, start < 0 ? i : (i - start + players.size()) % players.size()));
            for (ZoneType zone : List.of(ZoneType.Hand, ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Stack, ZoneType.Command)) {
                for (Card card : player.getCardsIn(zone, false)) {
                    if (zone == ZoneType.Hand && !view.canSee(card.getView())) { continue; }
                    if (zone == ZoneType.Command && !card.isEmblem() && !card.isCommander()) { continue; }
                    Long previous = timestamps.putIfAbsent(card.getId(), card.getGameTimestamp());
                    if (previous != null) {
                        if (previous != card.getGameTimestamp()) { throw new IllegalStateException("Observable card keys must be unique across incarnations"); }
                        continue;
                    }
                    Map<String, Object> facts = CardReader.read(card, view, game.getCombat());
                    facts.put("links", cardLinks(card, view));
                    cardFacts.add(facts);
                    String name = view.visibleName(card.getView());
                    if (name != null && zone != ZoneType.Command) {
                        known.add(data("id", view.alias(card.getId()), "name", name,
                                "owner", view.side(player), "zone", zone.name(), "token", card.isToken()));
                    }
                }
            }
            for (ZoneType zone : List.of(ZoneType.Hand, ZoneType.Library)) {
                zones.add(data("owner", view.side(player), "zone", zone.name(),
                        "total", player.getCardsIn(zone).size(),
                        "token_ids", player.getCardsIn(zone).stream().filter(Card::isToken)
                                .map(card -> view.rememberedReference(card.getId())).filter(Objects::nonNull)
                                .map(reference -> (Integer) reference.get("id")).sorted().toList()));
            }
            player.getCommanderDamage().forEach(entry -> {
                if (entry.getValue() > 0) {
                    commanderDamage.add(data("source", view.reference(entry.getKey()),
                            "target", view.reference(player), "amount", entry.getValue()));
                }
            });
        }
        String outcome = !game.isGameOver() ? "ongoing" : game.getOutcome().getWinningLobbyPlayer() == null ? "draw"
                : game.getOutcome().getWinningLobbyPlayer().equals(observer.getLobbyPlayer()) ? "win" : "loss";
        List<String> deck = game.getAge() == GameStage.BeforeMulligan
                ? observer.getRegisteredPlayer().getDeck().getMain().toFlatList().stream().map(card -> card.getName()).toList() : null;
        return data("game", data("active_player", view.side(phase.getPlayerTurn()),
                "starting_player", view.side(game.getStartingPlayer()), "native_format", game.getRules().getGameType().name(),
                "starting_life", observer.getStartingLife(), "day_time", game.getDayTime(),
                "in_combat", phase.inCombat(), "phase", phase.getPhase() == null ? null : phase.getPhase().name(),
                "turn", phase.getTurn(), "is_first_turn", phase.getTurn() == 1, "outcome", outcome),
                "players", playerFacts, "cards", cardFacts, "stack", stack(game, view),
                "effects", effects(game, view), "commander_damage", commanderDamage,
                "knowledge", data("visible", known, "zones", zones, "deck", deck));
    }

    private static Map<String, Object> cardLinks(Card card, ObservationFacade view) {
        var links = data("attached_to", refs(view, Collections.singletonList(card.getEntityAttachedTo())),
                "paired_with", refs(view, Collections.singletonList(card.getPairedWith())),
                "exiled_with", refs(view, Collections.singletonList(card.getExiledWith())),
                "crewed_by", refs(view, card.getCrewedByThisTurn()),
                "saddled_by", refs(view, card.getSaddledByThisTurn()),
                "goaded_by", refs(view, card.getGoaded()),
                "chosen_player", refs(view, Collections.singletonList(card.getView().getChosenPlayer())),
                "chosen_card", refs(view, card.getView().getChosenCards()),
                "linked_exile", refs(view, linkedCards(card)));
        List<Object> merged = new ArrayList<>();
        merged.addAll(refs(view, Arrays.asList(card.getMeldedWith(), card.getMergedToCard())));
        merged.addAll(refs(view, card.getMergedCards()));
        links.put("merged_with", merged);
        var game = card.getGame();
        var combat = game.getCombat();
        links.put("attacks", refs(view, Collections.singletonList(combat != null && combat.isAttacking(card) ? combat.getDefenderByAttacker(card) : null)));
        links.put("blocks", combat == null ? List.of() : refs(view, combat.getAttackersBlockedBy(card)));
        List<Object> canBlock = new ArrayList<>();
        if (card.isCreature() && card.isInZone(ZoneType.Battlefield)) {
            boolean attacking = combat != null && !combat.getAttackers().isEmpty();
            Iterable<Card> attackers = attacking ? combat.getAttackers() : game.getCardsIn(ZoneType.Battlefield);
            for (Card attacker : attackers) {
                if (!attacker.isCreature() || attacker.getController() == card.getController()) { continue; }
                if (attacking ? CombatUtil.canBlock(attacker, card, combat) : CombatUtil.canBlock(attacker, card, true)) {
                    var target = view.reference(attacker);
                    if (target != null) { canBlock.add(target); }
                }
            }
        }
        links.put("can_block", canBlock);
        return links;
    }

    private static List<Card> linkedCards(Card card) {
        List<Card> result = new ArrayList<>();
        for (Iterable<Card> cards : Arrays.asList(card.getExiledCards(), card.getUntilLeavesBattlefield(), card.getImprintedCards(), card.getEncodedCards())) {
            if (cards != null) { cards.forEach(result::add); }
        }
        return result;
    }

    private static List<Map<String, Object>> refs(ObservationFacade view, Iterable<?> values) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (values != null) {
            for (Object value : values) {
                var ref = view.reference(value);
                if (ref != null) { result.add(ref); }
            }
        }
        return result;
    }

    private static List<Object> stack(Game game, ObservationFacade view) {
        Map<SpellAbility, Integer> stackIds = new HashMap<>();
        game.getStack().forEach(item -> stackIds.put(item.getSpellAbility(), item.getId()));
        List<Object> result = new ArrayList<>();
        int position = 0;
        for (var item : game.getStack()) {
            var ability = item.getSpellAbility();
            Card host = ability.getHostCard();
            var identity = view.identity(host);
            boolean down = host.isFaceDown();
            var facts = data("id", item.getId(), "card_name", identity == null ? null : identity.getName(),
                    "scope", down && identity != null ? "observer_private" : "public", "controller", view.side(ability.getActivatingPlayer()),
                    "is_token", host.isToken(), "is_face_down", down,
                    "copied_name", identity == null ? null : PrintedText.copiedName(host),
                    "api", ability.getApi() == null ? null : ability.getApi().name(),
                    "stack_kind", item.isSpell() ? "spell" : item.isTrigger() ? "triggered_ability" : ability.isActivatedAbility() ? "activated_ability" : "other",
                    "ability_text", !item.isSpell() && identity != null ? PrintedText.ability(ability) : null,
                    "position", position++, "x_paid", ability.getXManaCostPaid() == null ? 0 : ability.getXManaCostPaid(),
                    "source", view.reference(host), "activating_player", view.reference(item.getActivatingPlayer()));
            boolean characteristics = (identity != null || down) && (!down || host.isInZone(ZoneType.Battlefield) || host.isInZone(ZoneType.Stack));
            facts.putAll(characteristics ? CardReader.features(host) : data("mana_value", null, "card_types", List.of(), "subtypes", List.of(), "colors", List.of(), "keywords", List.of()));
            List<Object> parts = new ArrayList<>();
            int targetPosition = 0;
            for (SpellAbility part = ability; part != null; part = part.getSubAbility()) {
                List<Object> targets = new ArrayList<>();
                var choices = part.getTargets();
                if (choices != null) {
                    List<GameObject> entities = new ArrayList<>(choices.getTargetCards());
                    choices.getTargetPlayers().forEach(entities::add);
                    choices.getTargetSpells().forEach(entities::add);
                    for (GameObject entity : entities) {
                        var ref = entity instanceof SpellAbility spell && stackIds.containsKey(spell)
                                ? data("kind", "stack_object", "id", stackIds.get(spell)) : view.reference(entity);
                        if (ref != null) {
                            targets.add(data("object", ref, "position", targetPosition, "divided", choices.getDividedValue(entity)));
                        }
                        targetPosition++;
                    }
                }
                parts.add(data("text", identity != null && !down ? PrintedText.action(part) : null, "targets", targets));
            }
            facts.put("parts", parts);
            result.add(facts);
        }
        return result;
    }

    private static List<Object> effects(Game game, ObservationFacade view) {
        List<Object> result = new ArrayList<>();
        for (Player player : game.getRegisteredPlayers()) {
            for (Card effect : player.getCardsIn(ZoneType.Command)) {
                if (effect.isImmutable() && !effect.isEmblem()) { result.add(commandEffect(effect, view)); }
            }
            for (int i = 0; i < game.getPhaseHandler().getExtraTurnForPlayer(player); i++) {
                result.add(data("id", "extra_turn:" + player.getId() + ":" + i, "controller", view.side(player),
                        "type", "extra_turn", "text", "extra turn", "targets", refs(view, List.of(player))));
            }
            int manaIndex = 0;
            for (var mana : player.getManaPool()) {
                var ability = mana.getManaAbility();
                List<String> restrictions = new ArrayList<>();
                if (ability != null) {
                    if (ability.getManaRestrictions() != null && !ability.getManaRestrictions().isEmpty()) { restrictions.add(ability.getManaRestrictions()); }
                    if (ability.getExtraManaRestriction() != null && !ability.getExtraManaRestriction().isEmpty()) { restrictions.add(ability.getExtraManaRestriction()); }
                    if (ability.isPersistentMana()) { restrictions.add("persistent mana"); }
                }
                if (!restrictions.isEmpty()) {
                    result.add(data("id", "mana:" + player.getId() + ":" + manaIndex, "controller", view.side(player),
                            "type", "mana_restriction", "text", PrintedText.clean(String.join("; ", restrictions)),
                            "source", view.reference(mana.getSourceCard()), "targets", refs(view, List.of(player))));
                }
                manaIndex++;
            }
        }
        int index = 0;
        for (var trigger : game.getTriggerHandler().getDelayedTriggers()) {
            Card host = trigger.getHostCard();
            Card source = host.getEffectSource();
            result.add(data("id", "delayed_trigger:" + index++, "controller", view.side(host.getController()),
                    "type", "delayed_trigger", "text", source == null || view.canIdentify(source.getView()) ? PrintedText.trigger(trigger) : null,
                    "duration", duration(host), "source", view.reference(source), "targets", List.of()));
        }
        return result;
    }

    static Map<String, Object> commandEffect(Card card, ObservationFacade view) {
        Card source = card.getEffectSource();
        Map<String, Object> sourceRef = source != null && view.canIdentify(source.getView()) ? view.reference(source) : null;
        // A remembered public source may be in hand; face-down objects cannot reuse that link.
        if (sourceRef == null && source != null && !source.isFaceDown()) {
            sourceRef = view.rememberedReference(source.getId());
        }
        List<Object> targets = new ArrayList<>();
        for (Object target : card.getRemembered()) {
            if (target instanceof Card remembered && view.canSee(remembered.getView())) { targets.add(view.reference(remembered)); }
        }
        targets.addAll(refs(view, card.getView().getChosenCards()));
        targets.addAll(refs(view, Collections.singletonList(card.getView().getChosenPlayer())));
        return data("id", "visible:effect:" + view.alias(card.getId()), "controller", view.side(card.getController()),
                "type", "command", "text", source == null || view.canIdentify(source.getView()) ? PrintedText.effect(card) : null,
                "duration", duration(card), "source", sourceRef, "targets", targets,
                "replacement", !card.getReplacementEffects().isEmpty(), "continuous", !card.getStaticAbilities().isEmpty(),
                "triggered", !card.getTriggers().isEmpty());
    }

    private static String duration(Card card) {
        var ability = card.getEffectSourceAbility();
        return ability == null ? null : ability.getParam("Duration");
    }
}
