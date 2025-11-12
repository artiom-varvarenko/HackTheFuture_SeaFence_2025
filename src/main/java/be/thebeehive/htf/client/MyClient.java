package be.thebeehive.htf.client;

import be.thebeehive.htf.library.HtfClient;
import be.thebeehive.htf.library.HtfClientListener;
import be.thebeehive.htf.library.protocol.client.SelectActionsClientMessage;
import be.thebeehive.htf.library.protocol.server.ErrorServerMessage;
import be.thebeehive.htf.library.protocol.server.GameEndedServerMessage;
import be.thebeehive.htf.library.protocol.server.GameRoundServerMessage;
import be.thebeehive.htf.library.protocol.server.WarningServerMessage;

import java.math.BigDecimal;
import java.util.*;

/**
 * Main decision logic for the submarine.
 */
public class MyClient implements HtfClientListener {

    private static final int MAX_ACTIONS_PER_ROUND = 4;
    private static final BigDecimal CREW_RESERVE = new BigDecimal("100");

    private static final BigDecimal LOW_HULL      = new BigDecimal("60000");
    private static final BigDecimal CRITICAL_HULL = new BigDecimal("30000");

    private static final BigDecimal LOW_CREW      = new BigDecimal("700");
    private static final BigDecimal CRITICAL_CREW = new BigDecimal("300");

    // When above these, we allow more aggressive, scaling-focused choices.
    private static final BigDecimal HEALTHY_HULL  = new BigDecimal("120000");
    private static final BigDecimal HEALTHY_CREW  = new BigDecimal("900");

    @Override
    public void onErrorServerMessage(HtfClient client, ErrorServerMessage msg) {
        System.err.println("ERROR from server: " + msg.getMsg());
    }

    @Override
    public void onWarningServerMessage(HtfClient client, WarningServerMessage msg) {
        System.err.println("WARNING from server: " + msg.getMsg());
    }

    @Override
    public void onGameEndedServerMessage(HtfClient client, GameEndedServerMessage msg) {
        System.out.println("Game ended at round " + msg.getRound());
        System.out.println("Leaderboard:");
        for (GameEndedServerMessage.LeaderboardTeam team : msg.getLeaderboard()) {
            System.out.printf("  %s | lastRound=%d | points=%s%n",
                    team.getName(), team.getLastRound(), team.getPoints());
        }
    }

    @Override
    public void onGameRoundServerMessage(HtfClient client, GameRoundServerMessage msg) throws Exception {
        GameRoundServerMessage.Submarine sub = msg.getOurSubmarine();
        GameRoundServerMessage.Values current = sub.getValues();
        List<GameRoundServerMessage.Action> actions = msg.getActions();
        List<GameRoundServerMessage.Effect> effects = msg.getEffects();

        if (ClientUtils.isDead(current)) {
            System.out.printf("Round %d | Submarine already destroyed. Sending no actions.%n", msg.getRound());
            client.send(new SelectActionsClientMessage(msg.getRoundId(), Collections.emptyList()));
            return;
        }

        BigDecimal hull = safe(current.getHullStrength());
        BigDecimal crew = safe(current.getCrewHealth());
        boolean aggressive = hull.compareTo(HEALTHY_HULL) > 0 && crew.compareTo(HEALTHY_CREW) > 0;
        boolean dangerAhead = effects.stream().anyMatch(this::isHarmfulEffect);

        System.out.printf(
                "%n=== Round %d START ===%n" +
                        "State: hull=%s, crew=%s, mode=%s, dangerAhead=%s, actions=%d, effects=%d%n",
                msg.getRound(),
                hull,
                crew,
                aggressive ? "AGG" : "DEF",
                dangerAhead,
                actions != null ? actions.size() : 0,
                effects != null ? effects.size() : 0
        );

        List<Long> chosenActions = planRoundActions(current, actions, effects);

        client.send(new SelectActionsClientMessage(msg.getRoundId(), chosenActions));

        System.out.printf(
                "Round %d | Hull: %s | Crew: %s | Danger: %s | Steps: %d | Chosen: %s%n",
                msg.getRound(),
                current.getHullStrength(),
                current.getCrewHealth(),
                dangerAhead,
                chosenActions.size(),
                chosenActions
        );
        System.out.println("=== Round " + msg.getRound() + " END ===");
    }

    /**
     * High-level planning for a single round:
     * 1. Cancel harmful effects when possible (prioritising what is most dangerous).
     * 2. Use remaining steps for beneficial actions (repairs/heals/upgrades),
     *    with a strong bias towards protecting crew and keeping hull out of danger zones.
     */
    private List<Long> planRoundActions(GameRoundServerMessage.Values startState,
                                        List<GameRoundServerMessage.Action> actions,
                                        List<GameRoundServerMessage.Effect> effects) {

        List<Long> chosen = new ArrayList<>();
        Set<Long> usedActionIds = new HashSet<>();

        GameRoundServerMessage.Values simulated = startState;
        int step = 1;

        // 1. Cancel harmful effects
        List<GameRoundServerMessage.Effect> harmfulEffects = findHarmfulEffects(effects, simulated);
        if (!harmfulEffects.isEmpty()) {
            System.out.println("  Harmful effects (sorted):");
            for (GameRoundServerMessage.Effect e : harmfulEffects) {
                GameRoundServerMessage.Values v = e.getValues();
                System.out.printf(
                        "    Effect id=%d step=%d dh=%s dc=%s%n",
                        e.getId(),
                        e.getStep(),
                        v != null ? v.getHullStrength() : null,
                        v != null ? v.getCrewHealth() : null
                );
            }
        }

        for (GameRoundServerMessage.Effect effect : harmfulEffects) {
            if (step > MAX_ACTIONS_PER_ROUND) break;
            if (step > effect.getStep()) continue; // too late to cancel for this step

            GameRoundServerMessage.Action counter = findCounterAction(effect, actions, usedActionIds);
            if (counter == null || counter.getValues() == null) continue;

            GameRoundServerMessage.Values counterValues = counter.getValues();

            // Simulate if we TAKE the counter
            GameRoundServerMessage.Values afterCounter =
                    ClientUtils.sumValues(simulated, counterValues);

            BigDecimal crewAfterCounter = safe(afterCounter.getCrewHealth());
            BigDecimal hullAfterCounter = safe(afterCounter.getHullStrength());

            // Simulate if we DO NOT counter and let the effect hit us
            GameRoundServerMessage.Values effectValues = effect.getValues();
            BigDecimal hullAfterNoCounter =
                    safe(simulated.getHullStrength()).add(safe(effectValues.getHullStrength()));
            BigDecimal crewAfterNoCounter =
                    safe(simulated.getCrewHealth()).add(safe(effectValues.getCrewHealth()));

            boolean effectWouldKill =
                    hullAfterNoCounter.compareTo(BigDecimal.ZERO) <= 0
                            || crewAfterNoCounter.compareTo(BigDecimal.ZERO) <= 0;

            System.out.printf(
                    "  [COUNTER EVAL] effect=%d step=%d | "
                            + "noCounter(hull=%s, crew=%s, kill=%s) vs "
                            + "counter(hull=%s, crew=%s)%n",
                    effect.getId(), effect.getStep(),
                    hullAfterNoCounter, crewAfterNoCounter, effectWouldKill,
                    hullAfterCounter, crewAfterCounter
            );

            // If the effect does NOT kill us, and the counter would drop crew below reserve,
            // skip this counter – saving crew for future rounds.
            if (!effectWouldKill && crewAfterCounter.compareTo(CREW_RESERVE) < 0) {
                System.out.printf(
                        "  [SKIP COUNTER] effect=%d action=%d drops crew below reserve (%s -> %s) while effect is non-lethal%n",
                        effect.getId(), counter.getId(),
                        safe(simulated.getCrewHealth()), crewAfterCounter
                );
                continue;
            }

            // Also keep the generic "don't instantly kill us" guard:
            if (ClientUtils.isDead(afterCounter)) {
                System.out.printf(
                        "  [SKIP COUNTER] effect=%d action=%d would kill us immediately%n",
                        effect.getId(), counter.getId()
                );
                continue;
            }

            //  Accept counter
            System.out.printf(
                    "  [COUNTER] step=%d effect=%d action=%d | hull=%s->%s crew=%s->%s%n",
                    step, effect.getId(), counter.getId(),
                    safe(simulated.getHullStrength()), hullAfterCounter,
                    safe(simulated.getCrewHealth()), crewAfterCounter
            );

            chosen.add(counter.getId());
            usedActionIds.add(counter.getId());
            simulated = afterCounter;
            step++;
        }

        // 2. Use remaining steps for the best beneficial actions
        while (step <= MAX_ACTIONS_PER_ROUND) {
            GameRoundServerMessage.Action best =
                    chooseBestBeneficialAction(actions, usedActionIds, simulated);

            if (best == null || best.getValues() == null) {
                System.out.printf("  No more beneficial actions found at step=%d%n", step);
                break;
            }

            GameRoundServerMessage.Values before = simulated;
            GameRoundServerMessage.Values after = ClientUtils.sumValues(simulated, best.getValues());

            if (ClientUtils.isDead(after)) {
                System.out.printf(
                        "  [SKIP PICK] step=%d action=%d would kill us: hull=%s->%s crew=%s->%s%n",
                        step,
                        best.getId(),
                        before.getHullStrength(), after.getHullStrength(),
                        before.getCrewHealth(), after.getCrewHealth()
                );
                break;
            }

            BigDecimal score = scoreAction(best.getValues(), simulated);
            System.out.printf(
                    "  [PICK] step=%d action=%d score=%s | hull=%s->%s crew=%s->%s%n",
                    step,
                    best.getId(),
                    score,
                    before.getHullStrength(), after.getHullStrength(),
                    before.getCrewHealth(), after.getCrewHealth()
            );

            chosen.add(best.getId());
            usedActionIds.add(best.getId());
            simulated = after;
            step++;
        }

        return chosen;
    }

    private BigDecimal safe(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    /**
     * Find harmful effects and sort them in a state-aware way:
     * 1) earlier steps first
     * 2) within the same step, prioritise the effect that
     *    is most dangerous given current hull/crew.
     */
    private List<GameRoundServerMessage.Effect> findHarmfulEffects(
            List<GameRoundServerMessage.Effect> effects,
            GameRoundServerMessage.Values state
    ) {
        List<GameRoundServerMessage.Effect> harmful = new ArrayList<>();
        if (effects == null) return harmful;

        for (GameRoundServerMessage.Effect e : effects) {
            if (isHarmfulEffect(e)) {
                harmful.add(e);
            }
        }

        final BigDecimal currentHull = safe(state.getHullStrength());
        final BigDecimal currentCrew = safe(state.getCrewHealth());

        harmful.sort((a, b) -> {
            int byStep = Integer.compare(a.getStep(), b.getStep());
            if (byStep != 0) return byStep;

            BigDecimal da = weightedDamage(a.getValues(), currentHull, currentCrew);
            BigDecimal db = weightedDamage(b.getValues(), currentHull, currentCrew);
            // more negative = more dangerous, so we want it first
            return db.compareTo(da);
        });

        return harmful;
    }

    private boolean isHarmfulEffect(GameRoundServerMessage.Effect e) {
        if (e == null || e.getValues() == null) return false;
        GameRoundServerMessage.Values v = e.getValues();
        return safe(v.getHullStrength()).compareTo(BigDecimal.ZERO) < 0
                || safe(v.getCrewHealth()).compareTo(BigDecimal.ZERO) < 0;
    }

    /**
     * How bad is this effect if we let it hit us,
     * taking current hull/crew into account.
     */
    private BigDecimal weightedDamage(GameRoundServerMessage.Values v,
                                      BigDecimal currentHull,
                                      BigDecimal currentCrew) {
        if (v == null) return BigDecimal.ZERO;

        BigDecimal dh = safe(v.getHullStrength());
        BigDecimal dc = safe(v.getCrewHealth());

        // Only consider negative parts as "damage".
        if (dh.compareTo(BigDecimal.ZERO) > 0) dh = BigDecimal.ZERO;
        if (dc.compareTo(BigDecimal.ZERO) > 0) dc = BigDecimal.ZERO;

        BigDecimal hullWeight = new BigDecimal("1.0");
        BigDecimal crewWeight = new BigDecimal("2.0");

        if (currentHull.compareTo(LOW_HULL) < 0) {
            hullWeight = hullWeight.add(new BigDecimal("1.0"));
        }
        if (currentHull.compareTo(CRITICAL_HULL) < 0) {
            hullWeight = hullWeight.add(new BigDecimal("1.5"));
        }

        if (currentCrew.compareTo(LOW_CREW) < 0) {
            crewWeight = crewWeight.add(new BigDecimal("2.0"));
        }
        if (currentCrew.compareTo(CRITICAL_CREW) < 0) {
            crewWeight = crewWeight.add(new BigDecimal("3.0"));
        }

        // damage is negative; weights just make dangerous things more negative
        return dh.multiply(hullWeight).add(dc.multiply(crewWeight));
    }

    private GameRoundServerMessage.Action findCounterAction(GameRoundServerMessage.Effect effect,
                                                            List<GameRoundServerMessage.Action> actions,
                                                            Set<Long> usedActionIds) {
        if (actions == null) return null;
        for (GameRoundServerMessage.Action action : actions) {
            if (action.getEffectId() == effect.getId() && !usedActionIds.contains(action.getId())) {
                return action;
            }
        }
        return null;
    }

    /**
     * Choose the best beneficial action given the current simulated state.
     * We heavily bias towards keeping crew safe, especially when crew is low.
     */
    private GameRoundServerMessage.Action chooseBestBeneficialAction(
            List<GameRoundServerMessage.Action> actions,
            Set<Long> usedActionIds,
            GameRoundServerMessage.Values state
    ) {
        GameRoundServerMessage.Action best = null;
        BigDecimal bestScore = BigDecimal.ZERO; // require strictly positive score

        BigDecimal currentHull = safe(state.getHullStrength());
        BigDecimal currentCrew = safe(state.getCrewHealth());

        boolean aggressive =
                currentHull.compareTo(HEALTHY_HULL) > 0 &&
                        currentCrew.compareTo(HEALTHY_CREW) > 0;

        System.out.printf(
                "  [EVAL] mode=%s, currentHull=%s, currentCrew=%s%n",
                aggressive ? "AGG" : "DEF",
                currentHull,
                currentCrew
        );

        for (GameRoundServerMessage.Action action : actions) {
            if (action == null || action.getValues() == null) continue;
            if (usedActionIds.contains(action.getId())) continue;

            GameRoundServerMessage.Values v = action.getValues();
            BigDecimal deltaHull = safe(v.getHullStrength());
            BigDecimal deltaCrew = safe(v.getCrewHealth());
            BigDecimal deltaMaxHull = safe(v.getMaxHullStrength());
            BigDecimal deltaMaxCrew = safe(v.getMaxCrewHealth());

            BigDecimal projectedHull = currentHull.add(deltaHull);
            BigDecimal projectedCrew = currentCrew.add(deltaCrew);

            // ---- Hard survival guards (fixed logic) ----

            // 1) If we are already in critical hull, never take *more* hull damage
            if (currentHull.compareTo(CRITICAL_HULL) <= 0
                    && deltaHull.compareTo(BigDecimal.ZERO) < 0) {
                System.out.printf(
                        "    [SKIP] action=%d would reduce critical hull: dh=%s%n",
                        action.getId(), deltaHull
                );
                continue;
            }

            // 2) If we are above critical, don't cross into critical with hull damage
            if (currentHull.compareTo(CRITICAL_HULL) > 0
                    && projectedHull.compareTo(CRITICAL_HULL) < 0
                    && deltaHull.compareTo(BigDecimal.ZERO) < 0) {
                System.out.printf(
                        "    [SKIP] action=%d would push hull into critical: %s -> %s%n",
                        action.getId(), currentHull, projectedHull
                );
                continue;
            }

            // 3) If crew is already critical, never take more crew damage
            if (currentCrew.compareTo(CRITICAL_CREW) <= 0
                    && deltaCrew.compareTo(BigDecimal.ZERO) < 0) {
                System.out.printf(
                        "    [SKIP] action=%d would reduce critical crew: dc=%s%n",
                        action.getId(), deltaCrew
                );
                continue;
            }

            // 4) If crew is low-but-not-critical, don't cross into critical with crew damage
            if (currentCrew.compareTo(CRITICAL_CREW) > 0
                    && currentCrew.compareTo(LOW_CREW) <= 0
                    && projectedCrew.compareTo(CRITICAL_CREW) < 0
                    && deltaCrew.compareTo(BigDecimal.ZERO) < 0) {
                System.out.printf(
                        "    [SKIP] action=%d would push crew into critical: %s -> %s%n",
                        action.getId(), currentCrew, projectedCrew
                );
                continue;
            }

            // ---- Score the action (uses aggressive/defensive weights) ----
            BigDecimal score = scoreAction(v, state);

            // Log candidate that passed the hard guards
            System.out.printf(
                    "    [CAND] action=%d dh=%s dc=%s dMaxH=%s dMaxC=%s | projHull=%s projCrew=%s | score=%s%n",
                    action.getId(),
                    deltaHull, deltaCrew, deltaMaxHull, deltaMaxCrew,
                    projectedHull, projectedCrew,
                    score
            );

            if (score.compareTo(bestScore) > 0) {
                bestScore = score;
                best = action;
            }
        }

        System.out.printf(
                "  [DECISION] mode=%s, bestAction=%s, bestScore=%s%n",
                aggressive ? "AGG" : "DEF",
                (best != null ? best.getId() : "none"),
                bestScore
        );

        return best;
    }


    /**
     * Scoring function: decide how good an action is,
     * depending on current hull / crew situation.
     *
     * Strong bias: crew survival > hull > max stats,
     * especially once crew starts getting low.
     */
    private BigDecimal scoreAction(GameRoundServerMessage.Values effect,
                                   GameRoundServerMessage.Values state) {

        BigDecimal deltaHull    = safe(effect.getHullStrength());
        BigDecimal deltaCrew    = safe(effect.getCrewHealth());
        BigDecimal deltaMaxHull = safe(effect.getMaxHullStrength());
        BigDecimal deltaMaxCrew = safe(effect.getMaxCrewHealth());

        BigDecimal currentHull  = safe(state.getHullStrength());
        BigDecimal currentCrew  = safe(state.getCrewHealth());

        boolean aggressive =
                currentHull.compareTo(HEALTHY_HULL) > 0 &&
                        currentCrew.compareTo(HEALTHY_CREW) > 0;

        // If crew is critical, absolutely no crew damage regardless of mode
        if (currentCrew.compareTo(CRITICAL_CREW) <= 0
                && deltaCrew.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.valueOf(-1_000_000);
        }

        BigDecimal hullWeight;
        BigDecimal crewWeight;
        BigDecimal maxHullWeight;
        BigDecimal maxCrewWeight;

        if (aggressive) {
            // Aggressive mode: prioritize long-term scaling more
            hullWeight    = new BigDecimal("1.0");
            crewWeight    = new BigDecimal("1.5");
            maxHullWeight = new BigDecimal("1.6");
            maxCrewWeight = new BigDecimal("1.2");
        } else {
            // Defensive mode: favor immediate survival / recovery
            hullWeight    = new BigDecimal("1.8");
            crewWeight    = new BigDecimal("2.2");
            maxHullWeight = new BigDecimal("0.4");
            maxCrewWeight = new BigDecimal("0.3");

            // If hull is low, care more about hull repairs
            if (currentHull.compareTo(LOW_HULL) < 0) {
                hullWeight = hullWeight.add(new BigDecimal("1.5"));
            }
            if (currentHull.compareTo(CRITICAL_HULL) < 0) {
                hullWeight = hullWeight.add(new BigDecimal("2.0"));
            }

            // If crew is low, heavily prioritize healing
            if (currentCrew.compareTo(LOW_CREW) < 0) {
                crewWeight = crewWeight.add(new BigDecimal("2.0"));
            }
            if (currentCrew.compareTo(CRITICAL_CREW) < 0) {
                crewWeight = crewWeight.add(new BigDecimal("3.0"));
            }

            // Penalise crew loss more when low
            if (deltaCrew.compareTo(BigDecimal.ZERO) < 0
                    && currentCrew.compareTo(LOW_CREW) < 0) {
                crewWeight = crewWeight.add(new BigDecimal("2.0"));
            }
        }

        BigDecimal score = BigDecimal.ZERO;
        score = score.add(deltaHull.multiply(hullWeight));
        score = score.add(deltaCrew.multiply(crewWeight));
        score = score.add(deltaMaxHull.multiply(maxHullWeight));
        score = score.add(deltaMaxCrew.multiply(maxCrewWeight));

        // Extra hull risk penalty only when we are *not* in aggressive mode
        if (!aggressive && deltaHull.compareTo(BigDecimal.ZERO) < 0) {
            BigDecimal riskFactor = BigDecimal.ONE;
            if (currentHull.compareTo(LOW_HULL) < 0) {
                riskFactor = riskFactor.add(new BigDecimal("1.0"));
            }
            if (currentHull.compareTo(CRITICAL_HULL) < 0) {
                riskFactor = riskFactor.add(new BigDecimal("2.0"));
            }
            score = score.subtract(deltaHull.abs().multiply(riskFactor));
        }

        return score;
    }
}
