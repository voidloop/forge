package forge.rl;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import forge.game.Game;
import forge.game.StaticEffect;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityMode;
import forge.game.zone.ZoneType;

/** Public continuous definitions, keeping hidden-dependent application unpublished. */
public final class ObservableContinuousEffects {
    private ObservableContinuousEffects() { }

    public record Definition(StaticAbility ability, StaticEffect application,
                             boolean applicationPublic, String duration) { }

    public static List<Definition> snapshot(final Game game, final Player observer,
                                            final Set<Integer> publicEffects) {
        final List<Definition> result = new ArrayList<>();
        for (Card source : game.getCardsInGame()) {
            if (!source.getView().canBeShownTo(observer.getView())
                    || !source.getView().canFaceDownBeShownTo(observer.getView())
                    || source.isImmutable() && !publicEffects.contains(source.getId())) {
                continue;
            }
            for (StaticAbility ability : source.getStaticAbilities()) {
                if (ability.checkMode(StaticAbilityMode.Continuous) && ability.zonesCheck()) {
                    result.add(new Definition(ability, game.getStaticEffects().getAppliedEffect(ability),
                            publicApplication(ability), duration(ability)));
                }
            }
        }
        return List.copyOf(result);
    }

    private static boolean publicApplication(final StaticAbility ability) {
        // Only unconditional, printed constant P/T modifiers publish causal membership.
        if (!Set.of("Mode", "Affected", "AddPower", "AddToughness", "Description")
                .containsAll(ability.getMapParams().keySet())) {
            return false;
        }
        if (!ability.hasParam("AddPower") && !ability.hasParam("AddToughness")) {
            return false;
        }
        final String affected = ability.getParam("Affected");
        if (affected == null || !Set.of("Creature", "Creature.YouCtrl", "Creature.OppCtrl")
                .contains(affected)) {
            return false;
        }
        for (String amount : List.of("AddPower", "AddToughness")) {
            if (ability.hasParam(amount) && !ability.getParam(amount).matches("[+-]?\\d+")) {
                return false;
            }
        }
        return true;
    }

    private static String duration(final StaticAbility ability) {
        final SpellAbility creator = ability.getHostCard().getEffectSourceAbility();
        if (creator != null) {
            return creator.getParam("Duration");
        }
        final Set<ZoneType> zones = ability.getActiveZone();
        return !ability.isCharacteristicDefining()
                && (zones == null || zones.equals(Set.of(ZoneType.Battlefield)))
                ? "UntilHostLeavesPlay" : null;
    }
}
