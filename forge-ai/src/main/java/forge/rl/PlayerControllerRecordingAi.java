package forge.rl;

import forge.LobbyPlayer;
import forge.ai.AiController;
import forge.ai.AiPlayDecision;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.spellability.Spell;
import forge.game.spellability.SpellAbility;

import java.util.Objects;

/** Minimal per-player probe for native effect casts. */
public final class PlayerControllerRecordingAi extends PlayerControllerAi {
    private final ObservedAi ai;

    public PlayerControllerRecordingAi(Game game, Player player, LobbyPlayer lobby, EffectCastProbe probe) {
        this(game, player, lobby, new ObservedAi(player, game, probe));
    }

    private PlayerControllerRecordingAi(Game game, Player player, LobbyPlayer lobby, ObservedAi ai) {
        super(game, player, lobby, ai);
        this.ai = ai;
    }

    @Override
    public boolean playSaFromPlayEffect(SpellAbility ability) {
        if (!(ability instanceof Spell spell)) { return super.playSaFromPlayEffect(ability); }
        Spell previous = ai.casting;
        ai.casting = spell;
        try {
            boolean cast = super.playSaFromPlayEffect(spell);
            ai.probe.completed(spell, cast);
            return cast;
        } finally {
            ai.casting = previous;
        }
    }

    private static final class ObservedAi extends AiController {
        private final EffectCastProbe probe;
        private Spell casting;

        private ObservedAi(Player player, Game game, EffectCastProbe probe) {
            super(player, game);
            this.probe = Objects.requireNonNull(probe);
        }

        @Override
        public AiPlayDecision canPlayFromEffectAI(Spell spell, boolean mandatory, boolean withoutMana) {
            if (spell != casting) { return super.canPlayFromEffectAI(spell, mandatory, withoutMana); }
            probe.beforeDecision(spell, mandatory, withoutMana);
            AiPlayDecision decision = super.canPlayFromEffectAI(spell, mandatory, withoutMana);
            probe.decided(spell, decision);
            return decision;
        }
    }
}
