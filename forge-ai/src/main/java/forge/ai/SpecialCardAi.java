/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.ai;

import com.google.common.base.Predicate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.google.common.base.Predicates;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import forge.card.MagicColor;
import forge.card.mana.ManaCost;
import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardFactoryUtil;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.card.CounterType;
import forge.game.cost.CostPart;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerPredicates;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.Aggregates;
import org.apache.commons.lang3.StringUtils;

/**
 * Special logic for individual cards
 * 
 * Specific methods for each card that requires special handling are stored in inner classes 
 * Each class should have a name based on the name of the card and ideally preceded with a 
 * single-line comment with the full English card name to make searching for them easier.
 * 
 * Class methods should return "true" if they are successful and have completed their task in full,
 * otherwise should return "false" to signal that the AI should not use the card under current
 * circumstances. A good convention to follow is to call the method "consider" if it's the only
 * method necessary, or considerXXXX if several methods do different tasks, and use at least two
 * mandatory parameters (Player ai, SpellAbility sa, in this order) and, if necessary, additional
 * parameters later.
 * 
 * If this class ends up being busy, consider splitting it into individual classes, each in its
 * own file, inside its own package, for example, forge.ai.cards.
 */
public class SpecialCardAi {

    // Birthing Pod, Natural Order, possibly can be expanded for other similar cards
    public static class BirthingPod {
        public static boolean consider(final Player ai, SpellAbility sa) {
            Card source = sa.getHostCard();
            PhaseHandler ph = ai.getGame().getPhaseHandler();
            boolean sacWorst = sa.getParam("AILogic").contains("SacWorst");
            boolean anyCMC = sa.getParam("AILogic").contains("AnyCMCGoal");
            
            if (!ph.is(PhaseType.MAIN2)) {
                // Should be given a chance to cast other spells as well as to use a previously upgraded creature
                return false;
            }
            
            String definedSac = StringUtils.split(source.getSVar("AIPreference"), "$")[1];
            String definedGoal = sa.hasParam("AISearchGoal") ? sa.getParam("AISearchGoal") : "Creature";

            String overridePrefix = definedGoal.contains(".") ? definedGoal + "+" : definedGoal + ".";

            CardCollection listToSac = CardLists.filter(ai.getCardsIn(ZoneType.Battlefield), CardPredicates.restriction(definedSac.split(","), ai, source, sa));
            listToSac.sort(!sacWorst ? CardLists.CmcComparatorInv : Collections.reverseOrder(CardLists.CmcComparatorInv));

            for (Card sacCandidate : listToSac) {
                int sacCMC = sacCandidate.getCMC();
                int goalCMC = sacCMC + 1;

                CardCollection listGoal = CardLists.filter(ai.getCardsIn(ZoneType.Library), CardPredicates.restriction(definedGoal.split(","), ai, source, sa));

                if (!anyCMC) {
                    // e.g. Birthing Pod - ensure we have a valid card to upgrade to
                    listGoal = CardLists.getValidCards(listGoal, overridePrefix + "cmcEQ" + goalCMC, source.getController(), source);
                } else {
                    // e.g. Natural Order - ensure we're upgrading to something better
                    listGoal = CardLists.getValidCards(listGoal, overridePrefix + "cmcGE" + goalCMC, source.getController(), source);
                }
                listGoal = CardLists.filter(listGoal, new Predicate<Card>() {
                    @Override
                    public boolean apply(final Card c) {
                        if (c.getType().isLegendary()) {
                            if (ai.isCardInPlay(c.getName())) {
                                return false;
                            }
                        }
                        return true;
                    }
                });
                
                if (!listGoal.isEmpty()) {
                    // make sure we're upgrading sacCMC->goalCMC
                    source.setSVar("AIPreferenceOverride", "Creature.cmcEQ" + sacCMC);
                    return true;
                }
            }

            // no candidates to upgrade
            return false;
        }
    }
    
    // Black Lotus and Lotus Bloom
    public static class BlackLotus {
        public static boolean consider(Player ai, SpellAbility sa, ManaCostBeingPaid cost) {
            CardCollection manaSources = ComputerUtilMana.getAvailableMana(ai, true);
            int numManaSrcs = manaSources.size();

            CardCollection allCards = CardLists.filter(ai.getAllCards(), Arrays.asList(CardPredicates.Presets.NON_TOKEN,
                    Predicates.not(CardPredicates.Presets.LANDS), CardPredicates.isOwner(ai)));

            int numHighCMC = CardLists.count(allCards, CardPredicates.greaterCMC(5));
            int numLowCMC = CardLists.count(allCards, CardPredicates.lessCMC(3));

            boolean isLowCMCDeck = numHighCMC <= 6 && numLowCMC >= 25;
            
            int minCMC = isLowCMCDeck ? 3 : 4; // probably not worth wasting a lotus on a low-CMC spell (<4 CMC), except in low-CMC decks, where 3 CMC may be fine
            int paidCMC = cost.getConvertedManaCost();
            if (paidCMC < minCMC) {
                if (paidCMC == 3 && numManaSrcs < 3) {
                    // if it's a CMC 3 spell and we're more than one mana source short for it, might be worth it anyway
                    return true;
                } 

                return false;
            }

            return true;
        }
    }

    // Chain of Smog
    public static class ChainOfSmog {
        public static boolean consider(Player ai, SpellAbility sa) {
            if (ai.getCardsIn(ZoneType.Hand).isEmpty()) {
                // to avoid failure to add to stack, provide a legal target opponent first (choosing random at this point)
                // TODO: this makes the AI target opponents with 0 cards in hand, but bailing from here causes a
                // "failed to add to stack" error, needs investigation and improvement.
                Player targOpp = Aggregates.random(ai.getOpponents());

                for (Player opp : ai.getOpponents()) {
                    if (!opp.getCardsIn(ZoneType.Hand).isEmpty()) {
                        targOpp = opp;
                        break;
                    }
                }

                sa.getParent().resetTargets();
                sa.getParent().getTargets().add(targOpp);
                return true;
            }

            return false;
        }
    }
        
    // Desecration Demon
    public static class DesecrationDemon {
        private static final int demonSacThreshold = Integer.MAX_VALUE; // if we're in dire conditions, sac everything from worst to best hoping to find an answer

        public static boolean considerSacrificingCreature(Player ai, SpellAbility sa) {
            CardCollection flyingCreatures = CardLists.filter(ai.getCardsIn(ZoneType.Battlefield), Predicates.and(CardPredicates.Presets.UNTAPPED, Predicates.or(CardPredicates.hasKeyword("Flying"), CardPredicates.hasKeyword("Reach"))));
            boolean hasUsefulBlocker = false;

            for (Card c : flyingCreatures) {
                if (!ComputerUtilCard.isUselessCreature(ai, c)) {
                    hasUsefulBlocker = true;
                }
            }

            if (ai.getLife() <= sa.getHostCard().getNetPower() && !hasUsefulBlocker) {
                return true;
            } else {
                return false;
            }
        }
        
        public static int getSacThreshold() {
            return demonSacThreshold;
        }
    }

    // Donate
    public static class Donate {
        public static boolean considerTargetingOpponent(Player ai, SpellAbility sa) {
            final Card donateTarget = ComputerUtil.getCardPreference(ai, sa.getHostCard(), "DonateMe", CardLists.filter(
                    ai.getCardsIn(ZoneType.Battlefield).threadSafeIterable(), CardPredicates.hasSVar("DonateMe")));
            if (donateTarget != null) {
                // first filter for opponents which can be targeted by SA
                final Iterable<Player> oppList = Iterables.filter(ai.getOpponents(),
                        PlayerPredicates.isTargetableBy(sa));

                // filter for player who does not have donate target already
                Iterable<Player> oppTarget = Iterables.filter(oppList,
                        PlayerPredicates.isNotCardInPlay(donateTarget.getName()));
                // fall back to previous list
                if (Iterables.isEmpty(oppTarget)) {
                    oppTarget = oppList;
                }

                // select player with less lands on the field (helpful for Illusions of Grandeur and probably Pacts too)
                Player opp = Collections.min(Lists.newArrayList(oppTarget),
                        PlayerPredicates.compareByZoneSize(ZoneType.Battlefield, CardPredicates.Presets.LANDS));

                if (opp != null) {
                    sa.resetTargets();
                    sa.getTargets().add(opp);
                    return true;
                }
                return true;
            }
            // No targets found to donate, so do nothing.
            return false;
        }

        public static boolean considerDonatingPermanent(Player ai, SpellAbility sa) {
            Card donateTarget = ComputerUtil.getCardPreference(ai, sa.getHostCard(), "DonateMe", CardLists.filter(ai.getCardsIn(ZoneType.Battlefield).threadSafeIterable(), CardPredicates.hasSVar("DonateMe")));
            if (donateTarget != null) {
                sa.resetTargets();
                sa.getTargets().add(donateTarget);
                return true;
            }

            // Should never get here because targetOpponent, called before targetPermanentToDonate, should already have made the AI bail
            System.err.println("Warning: Donate AI failed at SpecialCardAi.Donate#targetPermanentToDonate despite successfully targeting an opponent first.");
            return false;
        }
    }

    // Force of Will
    public static class ForceOfWill {
        public static boolean consider(Player ai, SpellAbility sa) {
            CardCollection blueCards = CardLists.filter(ai.getCardsIn(ZoneType.Hand), CardPredicates.isColor(MagicColor.BLUE));

            boolean isExileMode = false;
            for (CostPart c : sa.getPayCosts().getCostParts()) {
                if (c.toString().contains("Exile")) {
                    isExileMode = true; // the AI is trying to go for the "exile and pay life" alt cost
                    break;
                }
            }

            if (isExileMode) {
                if (blueCards.size() < 2) {
                    // Need to have something else in hand that is blue in addition to Force of Will itself,
                    // otherwise the AI will fail to play the card and the card will disappear from the pool
                    return false;
                } else if (CardLists.filter(blueCards, CardPredicates.lessCMC(3)).isEmpty()) {
                    // We probably need a low-CMC card to exile to it, exiling a higher CMC spell may be suboptimal
                    // since the AI does not prioritize/value cards vs. permission at the moment.
                    return false;
                }
            }

            return true;
        }
    }

    // Living Death (and possibly other similar cards using AILogic LivingDeath)
    public static class LivingDeath {
        public static boolean consider(Player ai, SpellAbility sa) {
            int aiBattlefieldPower = 0, aiGraveyardPower = 0;
            CardCollection aiCreaturesInGY = CardLists.filter(ai.getZone(ZoneType.Graveyard).getCards(), CardPredicates.Presets.CREATURES);

            if (aiCreaturesInGY.isEmpty()) {
                // nothing in graveyard, so cut short
                return false;
            }
            
            for (Card c : ai.getCreaturesInPlay()) {
                if (!ComputerUtilCard.isUselessCreature(ai, c)) {
                    aiBattlefieldPower += ComputerUtilCard.evaluateCreature(c);
                }
            }
            for (Card c : aiCreaturesInGY) {
                aiGraveyardPower += ComputerUtilCard.evaluateCreature(c);
            }

            int oppBattlefieldPower = 0, oppGraveyardPower = 0; 
            List<Player> opponents = ai.getOpponents();
            for (Player p : opponents) {
                int playerPower = 0;
                int tempGraveyardPower = 0;
                for (Card c : p.getCreaturesInPlay()) {
                    playerPower += ComputerUtilCard.evaluateCreature(c);
                }
                for (Card c : CardLists.filter(p.getZone(ZoneType.Graveyard).getCards(), CardPredicates.Presets.CREATURES)) {
                    tempGraveyardPower += ComputerUtilCard.evaluateCreature(c);
                }
                if (playerPower > oppBattlefieldPower) {
                    oppBattlefieldPower = playerPower;
                }
                if (tempGraveyardPower > oppGraveyardPower) {
                    oppGraveyardPower = tempGraveyardPower;
                }
            }

            // if we get more value out of this than our opponent does (hopefully), go for it
            return (aiGraveyardPower - aiBattlefieldPower) > (oppGraveyardPower - oppBattlefieldPower);
        }
    }

    // Necropotence
    public static class Necropotence {
        public static boolean consider(Player ai, SpellAbility sa) {
            Game game = ai.getGame();
            int computerHandSize = ai.getZone(ZoneType.Hand).size();
            int maxHandSize = ai.getMaxHandSize();

            if (!CardLists.filter(ai.getCardsIn(ZoneType.Battlefield), CardPredicates.nameEquals("Yawgmoth's Bargain")).isEmpty()) {
                // Prefer Yawgmoth's Bargain because AI is generally better with it

                // TODO: in presence of bad effects which deal damage when a card is drawn, probably better to prefer Necropotence instead?
                // (not sure how to detect the presence of such effects yet)
                return false;
            }

            PhaseHandler ph = game.getPhaseHandler();

            int exiledWithNecro = 1; // start with 1 because if this succeeds, one extra card will be exiled with Necro
            for (Card c : ai.getCardsIn(ZoneType.Exile)) {
                if (c.getExiledWith() != null && "Necropotence".equals(c.getExiledWith().getName()) && c.isFaceDown()) {
                    exiledWithNecro++;
                }
            }

            // TODO: Any other bad effects like that?
            boolean blackViseOTB = !CardLists.filter(game.getCardsIn(ZoneType.Battlefield), CardPredicates.nameEquals("Black Vise")).isEmpty();

            if (ph.getNextTurn().equals(ai) && ph.is(PhaseType.MAIN2)
                    && ai.getSpellsCastLastTurn() == 0 
                    && ai.getSpellsCastThisTurn() == 0
                    && ai.getLandsPlayedLastTurn() == 0) {
                // We're in a situation when we have nothing castable in hand, something needs to be done
                if (!blackViseOTB) {
                    // exile-loot +1 card when at max hand size, hoping to get a workable spell or land
                    return computerHandSize + exiledWithNecro - 1 == maxHandSize; 
                } else {
                    // Loot to 7 in presence of Black Vise, hoping to find what to do
                    // NOTE: can still currently get theoretically locked with 7 uncastable spells. Loot to 8 instead?
                    return computerHandSize + exiledWithNecro <= maxHandSize;
                }
            } else if (blackViseOTB && computerHandSize + exiledWithNecro - 1 >= 4) { 
                // try not to overdraw in presence of Black Vise
                return false; 
            } else if (computerHandSize + exiledWithNecro - 1 >= maxHandSize) {
                // Only draw until we reach max hand size
                return false;
            } else if (!ph.isPlayerTurn(ai) || !ph.is(PhaseType.MAIN2)) {
                // Only activate in AI's own turn (sans the exception above)
                return false;
            } 

            return true;
        }
    }

    // Nykthos, Shrine to Nyx
    public static class NykthosShrineToNyx {
        public static boolean consider(Player ai, SpellAbility sa) {
            Game game = ai.getGame();
            PhaseHandler ph = game.getPhaseHandler();
            if (!ph.isPlayerTurn(ai) || ph.getPhase().isBefore(PhaseType.MAIN2)) {
                // TODO: currently limited to Main 2, somehow improve to let the AI use this SA at other time?
                return false;
            }
            String prominentColor = ComputerUtilCard.getMostProminentColor(ai.getCardsIn(ZoneType.Battlefield));
            int devotion = CardFactoryUtil.xCount(sa.getHostCard(), "Count$Devotion." + prominentColor);
            int activationCost = sa.getPayCosts().getTotalMana().getCMC() + (sa.getPayCosts().hasTapCost() ? 1 : 0);

            // do not use this SA if devotion to most prominent color is less than its own activation cost + 1 (to actually get advantage)
            if (devotion < activationCost + 1) {
                return false;
            }

            final CardCollectionView cards = ai.getCardsIn(new ZoneType[] {ZoneType.Hand, ZoneType.Battlefield, ZoneType.Command});
            List<SpellAbility> all = ComputerUtilAbility.getSpellAbilities(cards, ai);

            int numManaSrcs = CardLists.filter(ComputerUtilMana.getAvailableMana(ai, true), CardPredicates.Presets.UNTAPPED).size();

            for (final SpellAbility testSa : ComputerUtilAbility.getOriginalAndAltCostAbilities(all, ai)) {
                ManaCost cost = testSa.getPayCosts().getTotalMana();
                byte colorProfile = cost.getColorProfile();
                
                if (cost.getCMC() == 0 && cost.countX() == 0) {
                    // no mana cost, no need to activate this SA then (additional mana not needed)
                    continue;
                } else if (colorProfile != 0 && (cost.getColorProfile() & MagicColor.fromName(prominentColor)) == 0) {
                    // does not feature prominent color, won't be able to pay for it with SA activated for this color
                    continue;
                } else if ((testSa.getPayCosts().getTotalMana().getCMC() > devotion + numManaSrcs - activationCost)) {
                    // the cost may be too high even if we activate this SA
                    continue;
                }

                if (testSa.getHostCard().getName().equals(sa.getHostCard().getName())) {
                    // prevent infinitely recursing own ability when testing AI play decision
                    continue;
                }

                testSa.setActivatingPlayer(ai);
                if (((PlayerControllerAi)ai.getController()).getAi().canPlaySa(testSa) == AiPlayDecision.WillPlay) {
                    // the AI is willing to play the spell
                    return true;
                }
            }

            return false; // haven't found anything to play with the excess generated mana
        }
    }

    // Timetwister
    public static class Timetwister {
        public static boolean consider(Player ai, SpellAbility sa) {
            final int aiHandSize = ai.getCardsIn(ZoneType.Hand).size();
            int maxOppHandSize = 0;

            final int HAND_SIZE_THRESHOLD = 3;

            for (Player p : ai.getOpponents()) {
                int handSize = p.getCardsIn(ZoneType.Hand).size();
                if (handSize > maxOppHandSize) {
                    maxOppHandSize = handSize;
                }
            }

            if (aiHandSize < HAND_SIZE_THRESHOLD || maxOppHandSize - aiHandSize > HAND_SIZE_THRESHOLD) {
                // use in case we're getting low on cards or if we're significantly behind our opponent in cards in hand
                return true;
            }

            return false;
        }
    }
    
    public static class UginTheSpiritDragon {
        public static boolean considerPWAbilityPriority(Player ai, SpellAbility sa, ZoneType origin, CardCollectionView oppType, CardCollectionView computerType) {
            Card source = sa.getHostCard();
            Game game = source.getGame();
            
            final int loyalty = source.getCounters(CounterType.LOYALTY);
            int x = -1, best = 0;
            Card single = null;
            for (int i = 0; i < loyalty; i++) {
                source.setSVar("ChosenX", "Number$" + i);
                oppType = CardLists.filterControlledBy(game.getCardsIn(origin), ai.getOpponents());
                oppType = AbilityUtils.filterListByType(oppType, sa.getParam("ChangeType"), sa);
                computerType = AbilityUtils.filterListByType(ai.getCardsIn(origin), sa.getParam("ChangeType"), sa);
                int net = ComputerUtilCard.evaluatePermanentList(oppType) - ComputerUtilCard.evaluatePermanentList(computerType) - i;
                if (net > best) {
                    x = i;
                    best = net;
                    if (oppType.size() == 1) {
                        single = oppType.getFirst();
                    } else {
                        single = null;
                    }
                }
            }
            // check if +1 would be sufficient
            if (single != null) {
                SpellAbility ugin_burn = null;
                for (final SpellAbility s : source.getSpellAbilities()) {
                    if (s.getApi() == ApiType.DealDamage) {
                        ugin_burn = s;
                        break;
                    }
                }
                if (ugin_burn != null) {
                    // basic logic copied from DamageDealAi::dealDamageChooseTgtC
                    if (ugin_burn.canTarget(single)) {
                        final boolean can_kill = single.getSVar("Targeting").equals("Dies")
                                || (ComputerUtilCombat.getEnoughDamageToKill(single, 3, source, false, false) <= 3)
                                        && !ComputerUtil.canRegenerate(ai, single)
                                        && !(single.getSVar("SacMe").length() > 0);
                        if (can_kill) {
                            return false;
                        }
                    }
                    // simple check to burn player instead of exiling planeswalker
                    if (single.isPlaneswalker() && single.getCurrentLoyalty() <= 3) {
                        return false;
                    }
                }
            }
             if (x == -1) {
                return false;
            }
            source.setSVar("ChosenX", "Number$" + x);
            return true;
        }
    }

    // Yawgmoth's Bargain
    public static class YawgmothsBargain {
        public static boolean consider(Player ai, SpellAbility sa) {
            Game game = ai.getGame();
            PhaseHandler ph = game.getPhaseHandler();

            int computerHandSize = ai.getZone(ZoneType.Hand).size();
            int maxHandSize = ai.getMaxHandSize();

            // TODO: Any other bad effects like that?
            boolean blackViseOTB = !CardLists.filter(game.getCardsIn(ZoneType.Battlefield), CardPredicates.nameEquals("Black Vise")).isEmpty();

            // TODO: Consider effects like "whenever a player draws a card, he loses N life" (e.g. Nekusar, the Mindraiser),
            //       and effects that draw an additional card whenever a card is drawn.

            if (ph.getNextTurn().equals(ai) && ph.is(PhaseType.END_OF_TURN) 
                    && ai.getSpellsCastLastTurn() == 0 
                    && ai.getSpellsCastThisTurn() == 0 
                    && ai.getLandsPlayedLastTurn() == 0) {
                // We're in a situation when we have nothing castable in hand, something needs to be done
                if (!blackViseOTB) {
                    // draw +1 card when at max hand size, hoping to draw a workable spell or land
                    return computerHandSize == maxHandSize;
                } else {
                    // draw cards hoping to draw answers even in presence of Black Vise if there's no valid play
                    // TODO: maybe limit to 1 or 2 cards at a time?
                    return computerHandSize + 1 <= maxHandSize; // currently draws to 7 cards
                }
            } else if (blackViseOTB && computerHandSize + 1 > 4) {
                    // try not to overdraw in presence of Black Vise
                    return false;
            } else if (computerHandSize + 1 > maxHandSize) {
                // Only draw until we reach max hand size
                return false;
            } else if (!ph.isPlayerTurn(ai)) {
                // Only activate in AI's own turn (sans the exception above)
                return false;
            } 

            return true;
        }
    }
    
    // Yawgmoth's Will (can potentially be expanded for other broadly similar effects too)
    public static class YawgmothsWill {
        public static boolean consider(Player ai, SpellAbility sa) {
            CardCollectionView cardsInGY = ai.getCardsIn(ZoneType.Graveyard);
            if (cardsInGY.size() == 0) {
                return false;
            }

            int minManaAdj = 2; // we want the AI to have some spare mana for possible other spells to cast
            float minCastableInGY = 3.0f; // we want the AI to have several castable cards in GY before attempting this effect
            List<SpellAbility> saList = ComputerUtilAbility.getSpellAbilities(cardsInGY, ai);
            int selfCMC = sa.getPayCosts().getCostMana().getMana().getCMC();

            float numCastable = 0.0f;
            for (SpellAbility ab : saList) {
                final Card src = ab.getHostCard();

                if (ab.getApi() == ApiType.Counter) {
                    // cut short considering to play counterspells via Yawgmoth's Will
                    continue;
                }
                if (ab.getHostCard().getName().equals(sa.getHostCard().getName())) {
                    // prevent infinitely recursing own ability when testing AI play decision
                    continue;
                }

                // check to see if the AI is willing to play this card
                final SpellAbility testAb = ab.copy();
                testAb.getRestrictions().setZone(ZoneType.Graveyard);
                testAb.setActivatingPlayer(ai);

                boolean willPlayAb = ((PlayerControllerAi)ai.getController()).getAi().canPlaySa(testAb) == AiPlayDecision.WillPlay;

                // Land drops are generally made by the AI in main 1 before casting spells, so testing for them is iffy.
                if (!src.getType().isLand() && willPlayAb) {
                    int CMC = ab.getPayCosts().getTotalMana() != null ? ab.getPayCosts().getTotalMana().getCMC() : 0;
                    int Xcount = ab.getPayCosts().getTotalMana() != null ? ab.getPayCosts().getTotalMana().countX() : 0;

                    if ((Xcount == 0 && CMC == 0) || ComputerUtilMana.canPayManaCost(ab, ai, selfCMC + minManaAdj)) {
                        if (src.isInstant() || src.isSorcery()) {
                            // instants and sorceries are one-shot, so only treat them as 1/2 value for the purpose of meeting minimum 
                            // castable cards in graveyard requirements 
                            numCastable += 0.5f;
                        } else {
                            numCastable += 1.0f;
                        }
                    }
                }
            }

            return numCastable >= minCastableInGY;
        }
    }

}
