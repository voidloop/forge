package forge.rl;

import forge.game.ability.effects.ChangeZoneEffect;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/** Render the current native search with its actual chooser, without stack overrides. */
public final class ObservableSearch extends ChangeZoneEffect {
    public String describe(final SpellAbility action, final Player chooser) {
        final SpellAbility detached = action.copy();
        detached.setSubAbility(null);
        detached.putParam("Chooser", "PlayerUID_" + chooser.getId());
        return super.getStackDescription(detached);
    }
}
