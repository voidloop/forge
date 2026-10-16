package forge.rl;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardPlayOption;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only permission facts, without disclosing conditional hidden membership. */
public final class ObservablePermissions {
    private ObservablePermissions() { }

    public record Member(Card card, CardPlayOption option) { }
    public record Grant(String nativeId, Card source, int position, Player beneficiary, String permission,
                        String text, String duration, Player durationPlayer,
                        boolean membershipComplete, List<Member> members) { }

    public static List<Grant> snapshot(final Game game, final Player observer,
                                       final Set<Integer> publicEffects, final Set<String> knownGrants) {
        final List<Grant> result = new ArrayList<>();
        final List<Card> publicCards = new ArrayList<>();
        for (Card card : game.getCardsInGame()) {
            if (card.isInZone(ZoneType.Battlefield) || card.isInZone(ZoneType.Graveyard)
                    || card.isInZone(ZoneType.Exile) || card.isInZone(ZoneType.Stack)
                    || card.isInZone(ZoneType.Command)
                    || card.getView().canBeShownTo(observer.getView())) {
                publicCards.add(card);
            }
        }
        for (Card source : publicCards) {
            if (!identityVisible(source, observer)
                    || source.isImmutable() && !publicEffects.contains(source.getId())) {
                continue;
            }
            int position = 0;
            for (StaticAbility ability : source.getStaticAbilities()) {
                final int traitPosition = position++;
                if (!ability.hasParam("MayPlay") || !ability.zonesCheck()) {
                    continue;
                }
                final List<Member> members = new ArrayList<>();
                Player beneficiary = ability.hasParam("MayPlayPlayer") ? null : source.getController();
                final boolean publicMembership = publicMembership(ability);
                if (publicMembership) {
                    for (Card card : publicCards) {
                        if (!identityVisible(card, observer)) {
                            continue;
                        }
                        CardPlayOption option = card.getMayPlay().get(ability);
                        if (option != null) {
                            members.add(new Member(card, option));
                        }
                    }
                }
                boolean complete = publicMembership;
                final List<ZoneType> zones = ability.hasParam("AffectedZone")
                        ? ZoneType.listValueOf(ability.getParam("AffectedZone")) : List.of(ZoneType.Battlefield);
                for (Card card : game.getCardsIn(zones)) {
                    if (!identityVisible(card, observer)) {
                        complete = false;
                    }
                }
                final SpellAbility creator = source.getEffectSourceAbility();
                result.add(new Grant("play:" + ability.getId(), source, traitPosition, beneficiary, "play",
                        ability.toString(), creator == null ? staticDuration(ability)
                        : duration(creator.getParam("Duration")),
                        creator == null ? null : creator.getActivatingPlayer(), complete, List.copyOf(members)));
            }
        }
        final Map<String, List<Member>> lookMembers = new LinkedHashMap<>();
        final Map<String, SpellAbility> lookSources = new LinkedHashMap<>();
        final Map<String, Player> lookPlayers = new LinkedHashMap<>();
        for (Card card : publicCards) {
            if (!card.isInZone(ZoneType.Exile)) {
                continue;
            }
            for (Map.Entry<SpellAbility, Player> entry : card.getMayLookFaceDownExileSources().entrySet()) {
                final SpellAbility source = entry.getKey();
                final String id = "look:" + source.getId();
                if (!identityVisible(source.getHostCard(), observer) && !knownGrants.contains(id)) {
                    continue;
                }
                lookMembers.computeIfAbsent(id, ignored -> new ArrayList<>()).add(new Member(card, null));
                lookSources.put(id, source);
                lookPlayers.put(id, entry.getValue());
            }
        }
        for (Map.Entry<String, List<Member>> entry : lookMembers.entrySet()) {
            final SpellAbility source = lookSources.get(entry.getKey());
            final boolean visible = identityVisible(source.getHostCard(), observer);
            result.add(new Grant(entry.getKey(), visible ? source.getHostCard() : null, 0,
                    lookPlayers.get(entry.getKey()), "look", visible ? source.toString() : null,
                    "while_exiled", null, true, List.copyOf(entry.getValue())));
        }
        return List.copyOf(result);
    }

    private static boolean identityVisible(final Card card, final Player observer) {
        return card.getView().canBeShownTo(observer.getView())
                && card.getView().canFaceDownBeShownTo(observer.getView());
    }

    private static boolean publicMembership(final StaticAbility ability) {
        // Opaque conditions can depend on hidden cards even when every affected card is public.
        if (!Set.of("Mode", "Affected", "AffectedZone", "MayPlay", "Description", "MayLookAt",
                "MayPlayWithoutManaCost", "MayPlayWithFlash", "MayPlayIgnoreColor", "MayPlayIgnoreType",
                "MayPlayIgnoreSnow", "MayPlayDontGrantZonePermissions", "MayPlayAltManaCost")
                .containsAll(ability.getMapParams().keySet())) {
            return false;
        }
        final String affected = ability.getParam("Affected");
        return affected != null && Set.of("Card", "Card.Self", "Card.IsRemembered", "Card.YouCtrl", "Card.YouOwn",
                "Creature", "Creature.YouCtrl", "Creature.YouOwn", "Card.Creature",
                "Land", "Land.YouCtrl", "Land.YouOwn", "Card.TopLibrary+YouCtrl", "Land.TopLibrary+YouCtrl")
                .contains(affected);
    }

    private static String staticDuration(final StaticAbility ability) {
        final Set<ZoneType> zones = ability.getActiveZone();
        return zones == null || zones.equals(Set.of(ZoneType.Battlefield))
                ? "while_source_on_battlefield" : "other";
    }

    private static String duration(final String value) {
        if (value == null) { return "other"; }
        return switch (value) {
            case "Permanent" -> "permanent";
            case "UntilEndOfTurn" -> "until_end_of_turn";
            case "UntilYourNextTurn" -> "until_your_next_turn";
            case "UntilYourNextUpkeep" -> "until_your_next_upkeep";
            case "UntilHostLeavesPlay" -> "while_source_on_battlefield";
            case "UntilEndOfCombat" -> "until_end_of_combat";
            default -> "other";
        };
    }
}
