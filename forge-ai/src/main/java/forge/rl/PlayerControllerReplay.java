package forge.rl;

import forge.LobbyPlayer;
import forge.ai.PlayerControllerAi;
import forge.ai.AiController;
import forge.card.mana.ManaCostShard;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.combat.Combat;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.mana.Mana;
import forge.game.trigger.WrappedAbility;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.PlayerZone;
import forge.game.zone.ZoneType;

import java.util.*;
import java.util.function.Supplier;

/** Record/replay boundary qualified only for the fixed creature fixture. */
final class PlayerControllerReplay extends PlayerControllerAi {
    private final IGameReplayCallback callback;

    PlayerControllerReplay(Game game, Player player, LobbyPlayer lobby, IGameReplayCallback callback) {
        super(game, player, lobby, new AiController(player, game) {
            @Override protected void onEvaluationFailure(Exception failure) {
                throw new IllegalStateException("AI evaluation failed; recording is incomplete", failure);
            }
        });
        this.callback = Objects.requireNonNull(callback);
    }

    private List<String> choose(String kind, List<String> context, Supplier<List<String>> original) {
        List<String> saved = callback.before(kind, context);
        if (saved != null) {
            if (saved.isEmpty()) { throw new IllegalArgumentException("Missing RNG checkpoint"); }
            List<String> answer = List.copyOf(saved.subList(1, saved.size()));
            validateShape(kind, context, answer);
            ReplayRandom.restore(saved.get(0));
            return answer;
        }
        List<String> answer = original.get();
        validateShape(kind, context, answer);
        callback.after(answer, ReplayRandom.save());
        return answer;
    }

    private static void validateShape(String kind, List<String> context, List<String> answer) {
        if (Set.of("starting_player", "starting_hand", "mulligan", "mana_pool").contains(kind)
                && answer.size() != 1) { throw new IllegalArgumentException("Expected one answer: " + kind); }
        if (Set.of("mana_source", "priority").contains(kind) && answer.size() > 1) {
            throw new IllegalArgumentException("Expected at most one answer: " + kind);
        }
        if (kind.startsWith("order_") || kind.startsWith("zone_order:")) {
            if (answer.size() != context.size() || !new HashSet<>(answer).equals(new HashSet<>(context))) {
                throw new IllegalArgumentException("Expected a permutation: " + kind);
            }
        }
    }

    private static List<String> ids(Iterable<Card> cards) {
        List<String> result = new ArrayList<>();
        for (Card card : cards) { result.add(Integer.toString(card.getId())); }
        return result;
    }

    private CardCollection cards(List<String> answer, Iterable<Card> available) {
        Map<String, Card> bindings = new HashMap<>();
        for (Card card : available) { bindings.put(Integer.toString(card.getId()), card); }
        CardCollection result = new CardCollection();
        for (String id : answer) {
            Card card = bindings.get(id);
            if (card == null || result.contains(card)) { throw new IllegalArgumentException("Invalid recorded card " + id); }
            result.add(card);
        }
        return result;
    }

    @Override public Player chooseStartingPlayer(boolean first) {
        List<Player> players = getGame().getRegisteredPlayers();
        List<String> context = players.stream().map(p -> Integer.toString(p.getId())).toList();
        String selected = choose("starting_player", context,
                () -> List.of(Integer.toString(super.chooseStartingPlayer(first).getId()))).get(0);
        return players.stream().filter(p -> Integer.toString(p.getId()).equals(selected)).findFirst().orElseThrow();
    }

    @Override public PlayerZone chooseStartingHand(List<PlayerZone> zones) {
        int index = Integer.parseInt(choose("starting_hand", List.of(Integer.toString(zones.size())),
                () -> List.of(Integer.toString(zones.indexOf(super.chooseStartingHand(zones))))).get(0));
        return zones.get(index);
    }

    @Override public boolean mulliganKeepHand(Player first, int count) {
        String answer = choose("mulligan", List.of(Integer.toString(first.getId()), Integer.toString(count)),
                () -> List.of(Boolean.toString(super.mulliganKeepHand(first, count)))).get(0);
        if (!answer.equals("true") && !answer.equals("false")) { throw new IllegalArgumentException("Invalid mulligan"); }
        return Boolean.parseBoolean(answer);
    }

    @Override public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int count) {
        CardCollection available = new CardCollection(hand);
        List<String> context = new ArrayList<>(ids(hand));
        context.add("count:" + count);
        CardCollection chosen = cards(choose("mulligan_bottom", context,
                () -> ids(super.tuckCardsViaMulligan(hand, count))), available);
        if (chosen.size() != count) { throw new IllegalArgumentException("Wrong mulligan count"); }
        // Preserve the original method's mutation: all but its last selection are removed.
        for (int i = 0; i + 1 < chosen.size(); i++) { hand.remove(chosen.get(i)); }
        return chosen;
    }

    @Override public List<SpellAbility> chooseSaToActivateFromOpeningHand(List<SpellAbility> options) {
        if (!options.isEmpty()) { throw new UnsupportedOperationException("Opening abilities"); }
        return List.of();
    }

    @Override public List<SpellAbility> chooseSpellAbilityToPlay() {
        CardCollection hand = new CardCollection(player.getCardsIn(ZoneType.Hand));
        List<SpellAbility> selected = new ArrayList<>();
        List<String> answer = choose("priority", ids(hand), () -> {
            List<SpellAbility> nativeAnswer = super.chooseSpellAbilityToPlay();
            if (nativeAnswer != null) { selected.addAll(nativeAnswer); }
            return selected.stream().map(sa -> Integer.toString(sa.getHostCard().getId())).toList();
        });
        if (answer.isEmpty()) { return null; }
        if (!selected.isEmpty()) { return selected; }
        for (Card card : cards(answer, hand)) {
            List<SpellAbility> abilities = card.getAllPossibleAbilities(player, true).stream()
                    .filter(sa -> sa.isLandAbility() || sa.isSpell()).toList();
            if (abilities.size() != 1) { throw new UnsupportedOperationException("Non-vanilla play " + card.getName()); }
            SpellAbility ability = abilities.get(0);
            ability.setActivatingPlayer(player);
            if (!ability.canPlay()) { throw new IllegalStateException("Recorded action is not playable"); }
            selected.add(ability);
        }
        return selected;
    }

    @Override public SpellAbility chooseManaAbilityForPayment(ManaCostBeingPaid cost, SpellAbility ability,
            ManaCostShard shard, Collection<SpellAbility> options, boolean checkCosts) {
        List<String> context = options.stream().map(sa -> Integer.toString(sa.getHostCard().getId())).toList();
        List<String> answer = choose("mana_source", context, () -> {
            SpellAbility selected = super.chooseManaAbilityForPayment(cost, ability, shard, options, checkCosts);
            return selected == null ? List.of() : List.of(Integer.toString(selected.getHostCard().getId()));
        });
        if (answer.isEmpty()) { return null; }
        return options.stream().filter(sa -> Integer.toString(sa.getHostCard().getId()).equals(answer.get(0)))
                .findFirst().orElseThrow();
    }

    @Override public void declareAttackers(Player attacker, Combat combat) {
        List<String> answer = choose("attackers", ids(attacker.getCreaturesInPlay()), () -> {
            super.declareAttackers(attacker, combat);
            return ids(combat.getAttackers());
        });
        if (combat.getAttackers().isEmpty()) {
            Player defender = attacker.getSingleOpponent();
            for (Card card : cards(answer, attacker.getCreaturesInPlay())) { combat.addAttacker(card, defender); }
        } else if (!ids(combat.getAttackers()).equals(answer)) { throw new IllegalStateException("Attackers differ"); }
    }

    @Override public void declareBlockers(Player defender, Combat combat) {
        List<String> context = new ArrayList<>(ids(defender.getCreaturesInPlay()));
        context.add("attackers"); context.addAll(ids(combat.getAttackers()));
        List<String> answer = choose("blockers", context, () -> {
            super.declareBlockers(defender, combat);
            List<String> result = new ArrayList<>();
            for (Card attacker : combat.getAttackers()) {
                for (Card blocker : combat.getBlockers(attacker)) { result.add(attacker.getId() + ":" + blocker.getId()); }
            }
            return result;
        });
        Set<String> assigned = new HashSet<>();
        for (String pair : answer) {
            String[] ids = pair.split(":", -1);
            if (ids.length != 2 || !assigned.add(ids[1])) { throw new IllegalArgumentException("Invalid blocker pair"); }
            Card attacker = cards(List.of(ids[0]), combat.getAttackers()).get(0);
            Card blocker = cards(List.of(ids[1]), defender.getCreaturesInPlay()).get(0);
            if (!combat.getBlockers(attacker).contains(blocker)) { combat.addBlocker(attacker, blocker); }
        }
    }

    @Override public CardCollection orderBlockers(Card attacker, CardCollection blockers) {
        return cards(choose("order_blockers", ids(blockers), () -> ids(super.orderBlockers(attacker, blockers))), blockers);
    }
    @Override public CardCollection orderAttackers(Card blocker, CardCollection attackers) {
        return cards(choose("order_attackers", ids(attackers), () -> ids(super.orderAttackers(blocker, attackers))), attackers);
    }
    @Override public Map<Card, Integer> assignCombatDamage(Card attacker, CardCollectionView blockers,
            CardCollectionView remaining, int damage, GameEntity defender, boolean overrideOrder) {
        List<String> context = new ArrayList<>(ids(blockers));
        context.add("attacker:" + attacker.getId()); context.add("damage:" + damage);
        List<Map<Card, Integer>> original = new ArrayList<>();
        List<String> answer = choose("combat_damage", context, () -> {
            Map<Card, Integer> nativeAnswer = super.assignCombatDamage(attacker, blockers, remaining, damage, defender, overrideOrder);
            original.add(nativeAnswer);
            return nativeAnswer.entrySet().stream()
                    .map(e -> (e.getKey() == null ? "player" : Integer.toString(e.getKey().getId())) + ":" + e.getValue()).toList();
        });
        if (!original.isEmpty()) { return original.get(0); }
        Map<Card, Integer> result = new LinkedHashMap<>();
        int total = 0;
        for (String pair : answer) {
            String[] parts = pair.split(":", -1);
            if (parts.length != 2) { throw new IllegalArgumentException("Invalid damage pair"); }
            Card target = parts[0].equals("player") ? null : cards(List.of(parts[0]), blockers).get(0);
            int amount = Integer.parseInt(parts[1]);
            if (amount < 0 || result.containsKey(target)) { throw new IllegalArgumentException("Invalid damage"); }
            result.put(target, amount); total = Math.addExact(total, amount);
        }
        if (total != damage) { throw new IllegalArgumentException("Wrong damage total"); }
        return result;
    }

    @Override public CardCollectionView chooseCardsToDiscardToMaximumHandSize(int count) {
        CardCollection hand = new CardCollection(player.getCardsIn(ZoneType.Hand));
        CardCollection answer = cards(choose("discard_to_hand_size", ids(hand),
                () -> ids(super.chooseCardsToDiscardToMaximumHandSize(count))), hand);
        if (answer.size() != count) { throw new IllegalArgumentException("Wrong discard count"); }
        return answer;
    }
    @Override public CardCollectionView orderMoveToZoneList(CardCollectionView available, ZoneType zone, SpellAbility source) {
        return cards(choose("zone_order:" + zone, ids(available),
                () -> ids(super.orderMoveToZoneList(available, zone, source))), available);
    }

    @Override public Mana chooseManaFromPool(List<Mana> options) {
        List<String> context = options.stream().map(m -> m.getSourceCard().getId() + ":" + m.getColor()).toList();
        int index = Integer.parseInt(choose("mana_pool", context,
                () -> List.of(Integer.toString(options.indexOf(super.chooseManaFromPool(options))))).get(0));
        return options.get(index);
    }

    @Override public void orderAndPlaySimultaneousSa(List<SpellAbility> abilities) {
        if (!abilities.isEmpty()) { throw new UnsupportedOperationException("Triggers/copies outside replay fixture"); }
    }
    @Override public boolean playTrigger(Card host, WrappedAbility ability, boolean mandatory) {
        throw new UnsupportedOperationException("Triggers outside replay fixture");
    }
    @Override public boolean playSaFromPlayEffect(SpellAbility ability) {
        throw new UnsupportedOperationException("Effect casts outside replay fixture");
    }
    @Override public void playSpellAbilityNoStack(SpellAbility ability, boolean canSetupTargets) {
        throw new UnsupportedOperationException("No-stack effects outside replay fixture");
    }
}
