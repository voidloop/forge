package forge.rl;

import forge.ai.ComputerUtilMana;
import forge.card.MagicColor;
import forge.card.mana.ManaCostShard;
import forge.game.GameActionUtil;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostPayment;
import forge.game.cost.CostTap;
import forge.game.mana.Mana;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.mana.ManaPool;
import forge.game.player.Player;
import forge.game.replacement.ReplacementType;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityManaConvert;
import forge.game.trigger.TriggerType;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/** Read-only mana feasibility for the declared fixed-color, basic-land slice. */
final class ActionMana {
    private static final EnumSet<ManaCostShard> SIMPLE_SHARDS = EnumSet.of(
            ManaCostShard.WHITE, ManaCostShard.BLUE, ManaCostShard.BLACK,
            ManaCostShard.RED, ManaCostShard.GREEN, ManaCostShard.COLORLESS, ManaCostShard.GENERIC);

    private ActionMana() { }

    static boolean canPay(SpellAbility action, Player payer) {
        // shortcut: complex mana production/payment stays unsupported here; extend before widening the curriculum.
        for (Card card : payer.getGame().getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
            if (card.getReplacementEffects().stream().anyMatch(effect -> effect.getMode() == ReplacementType.ProduceMana)
                    || card.getTriggers().stream().anyMatch(trigger -> trigger.getMode() == TriggerType.TapsForMana
                            || trigger.getMode() == TriggerType.ManaAdded)) {
                throw new UnsupportedOperationException("Mana production modifiers need another mana slice");
            }
        }
        Card source = action.getHostCard();
        if (source.hasKeyword("Convoke") || source.hasKeyword("Improvise")
                || source.hasKeyword("Delve") || action.hasParam("Announce")) {
            throw new UnsupportedOperationException("Alternative mana payment needs another mana slice");
        }
        Zone previousCastFrom = source.getCastFrom();
        ManaCostBeingPaid remaining;
        try {
            remaining = ComputerUtilMana.calculateManaCost(action.getPayCosts(), action, payer, true, 0, false);
        } finally {
            source.setCastFrom(previousCastFrom);
        }
        for (ManaCostShard shard : remaining.getDistinctShards()) {
            if (!SIMPLE_SHARDS.contains(shard)) {
                throw new UnsupportedOperationException("This mana symbol needs another payment slice");
            }
        }
        ManaPool conversion = new ManaPool(payer);
        StaticAbilityManaConvert.manaConvert(conversion, payer, source, action);
        for (byte color : forge.card.mana.ManaAtom.MANATYPES) {
            if (conversion.getPossibleColorUses(color) != color) {
                throw new UnsupportedOperationException("Mana conversion needs another payment slice");
            }
        }
        List<Mana> available = new ArrayList<>();
        for (Mana mana : payer.getManaPool()) {
            if (mana.meetsManaRestrictions(action) && action.allowsPayingWithShard(mana.getSourceCard(), mana.getColor())) {
                available.add(mana);
            }
        }
        for (Card card : CardCollection.combine(payer.getCardsIn(ZoneType.Battlefield), payer.getCardsIn(ZoneType.Hand))) {
            for (SpellAbility original : card.getManaAbilities()) {
                SpellAbility mana = original.copy(payer);
                if (!mana.canPlay()) {
                    continue;
                }
                if (!card.isBasicLand() || card.getManaAbilities().size() != 1 || mana.getSubAbility() != null) {
                    throw new UnsupportedOperationException("This mana source needs another mana slice");
                }
                for (CostPart cost : mana.getPayCosts().getCostParts()) {
                    if (!(cost instanceof CostTap) && !(cost instanceof CostPartMana && mana.getPayCosts().getTotalMana().getCMC() == 0)) {
                        throw new UnsupportedOperationException("This mana activation cost needs another mana slice");
                    }
                }
                if (!CostPayment.canPayAdditionalCosts(mana.getPayCosts(), mana, false, payer)) {
                    continue;
                }
                String produced = GameActionUtil.generatedTotalMana(mana);
                if (produced.length() != 1 || !"WUBRGC".contains(produced)) {
                    throw new UnsupportedOperationException("Variable mana production needs another mana slice");
                }
                Mana globe = new Mana(MagicColor.fromName(produced), card, mana.getManaPart(), payer);
                if (globe.meetsManaRestrictions(action) && action.allowsPayingWithShard(card, globe.getColor())) {
                    available.add(globe);
                }
            }
        }
        for (Mana mana : available) {
            if (remaining.isNeeded(mana, conversion)) {
                remaining.payMana(mana, conversion);
            }
        }
        return remaining.isPaid();
    }
}
