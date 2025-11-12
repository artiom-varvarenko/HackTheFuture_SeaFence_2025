package be.thebeehive.htf.client;

import be.thebeehive.htf.library.protocol.server.GameRoundServerMessage;
import be.thebeehive.htf.library.protocol.server.GameRoundServerMessage.Action;
import be.thebeehive.htf.library.protocol.server.GameRoundServerMessage.Effect;
import be.thebeehive.htf.library.protocol.server.GameRoundServerMessage.Values;

import java.math.BigDecimal;
import java.util.*;

/**
 * Simplified DecisionEngine focusing on survival.
 * Strategy: Block deadly threats > Heal critical damage > Do nothing (save slots)
 */
public class DecisionEngine {

    private static final int MAX_ACTIONS_PER_ROUND = 4;

    // Critical thresholds for emergency healing
    private static final BigDecimal CRITICAL_HULL_PERCENT = new BigDecimal("0.20");
    private static final BigDecimal CRITICAL_CREW_PERCENT = new BigDecimal("0.25");

    private final StringBuilder traceLog;

    public DecisionEngine() {
        this.traceLog = new StringBuilder();
    }

    /**
     * Main entry point: plan actions for the round.
     */
    public List<Long> planActions(GameRoundServerMessage msg) {
        Values state = msg.getOurSubmarine().getValues();
        List<Action> actions = msg.getActions();
        List<Effect> effects = msg.getEffects();

        traceLog.append(String.format("R%d H:%s/%s C:%s/%s | ",
                msg.getRound(),
                state.getHullStrength(), state.getMaxHullStrength(),
                state.getCrewHealth(), state.getMaxCrewHealth()));

        List<Long> chosen = new ArrayList<>();
        Set<Long> used = new HashSet<>();

        // Phase 1: Block ALL threats that would kill us
        Map<Integer, List<Effect>> effectsByStep = groupEffectsByStep(effects);
        Values simState = state;

        for (int step = 1; step <= getMaxEffectStep(effectsByStep); step++) {
            List<Effect> stepEffects = effectsByStep.get(step);
            if (stepEffects == null) continue;

            for (Effect effect : stepEffects) {
                // Check if this effect would kill us
                Values afterEffect = applyValues(simState, effect.getValues());

                if (isDead(afterEffect)) {
                    // Find blocker for this deadly effect
                    Action blocker = findBlocker(effect, actions, used);
                    if (blocker != null && chosen.size() < MAX_ACTIONS_PER_ROUND) {
                        chosen.add(blocker.getId());
                        used.add(blocker.getId());
                        simState = applyValues(simState, blocker.getValues());
                        traceLog.append(String.format("Block(e%d,a%d) ", effect.getId(), blocker.getId()));
                        break; // Only one blocker per step
                    } else {
                        // Can't block, try emergency heal
                        Action heal = findBestHeal(actions, used);
                        if (heal != null && chosen.size() < MAX_ACTIONS_PER_ROUND) {
                            chosen.add(heal.getId());
                            used.add(heal.getId());
                            simState = applyValues(simState, heal.getValues());
                            traceLog.append(String.format("EmergHeal(a%d) ", heal.getId()));
                        } else {
                            traceLog.append("NoSolution ");
                        }
                    }
                } else {
                    // Not deadly, but apply the effect to simulation
                    simState = applyValues(simState, effect.getValues());
                }
            }
        }

        // Phase 2: Emergency heal if critically low AFTER blocking threats
        if (chosen.size() < MAX_ACTIONS_PER_ROUND && isCritical(simState)) {
            Action heal = findBestHeal(actions, used);
            if (heal != null) {
                Values afterHeal = applyValues(simState, heal.getValues());
                if (!isDead(afterHeal)) {
                    chosen.add(heal.getId());
                    used.add(heal.getId());
                    simState = afterHeal;
                    traceLog.append(String.format("CritHeal(a%d) ", heal.getId()));
                }
            }
        }

        // Phase 3: One repair if very safe and below 50%
        if (chosen.size() < MAX_ACTIONS_PER_ROUND && !isCritical(simState) && isBelowHalf(simState)) {
            Action repair = findBestRepair(actions, used);
            if (repair != null) {
                Values afterRepair = applyValues(simState, repair.getValues());
                if (!isDead(afterRepair)) {
                    chosen.add(repair.getId());
                    used.add(repair.getId());
                    traceLog.append(String.format("Repair(a%d) ", repair.getId()));
                }
            }
        }

        traceLog.append(String.format("=> [%s]\n", chosen));
        return chosen;
    }

    /**
     * Get the trace log for this decision.
     */
    public String getTraceLog() {
        return traceLog.toString();
    }

    private Map<Integer, List<Effect>> groupEffectsByStep(List<Effect> effects) {
        Map<Integer, List<Effect>> map = new HashMap<>();
        for (Effect e : effects) {
            map.computeIfAbsent(e.getStep(), k -> new ArrayList<>()).add(e);
        }
        return map;
    }

    private int getMaxEffectStep(Map<Integer, List<Effect>> effectsByStep) {
        return effectsByStep.keySet().stream().max(Integer::compareTo).orElse(0);
    }

    private Action findBlocker(Effect effect, List<Action> actions, Set<Long> used) {
        Action bestBlocker = null;
        BigDecimal lowestCost = new BigDecimal("999999");

        for (Action action : actions) {
            if (action.getEffectId() == effect.getId() && !used.contains(action.getId())) {
                // Calculate cost (negative values in hull/crew)
                BigDecimal cost = BigDecimal.ZERO;
                if (action.getValues() != null) {
                    Values v = action.getValues();
                    if (v.getHullStrength() != null && v.getHullStrength().compareTo(BigDecimal.ZERO) < 0) {
                        cost = cost.add(v.getHullStrength().abs());
                    }
                    if (v.getCrewHealth() != null && v.getCrewHealth().compareTo(BigDecimal.ZERO) < 0) {
                        cost = cost.add(v.getCrewHealth().abs().multiply(new BigDecimal("2")));
                    }
                }

                if (cost.compareTo(lowestCost) < 0) {
                    lowestCost = cost;
                    bestBlocker = action;
                }
            }
        }
        return bestBlocker;
    }

    private Action findBestHeal(List<Action> actions, Set<Long> used) {
        Action best = null;
        BigDecimal bestScore = BigDecimal.ZERO;

        for (Action action : actions) {
            if (used.contains(action.getId()) || action.getValues() == null) continue;
            if (action.getEffectId() != -1) continue; // Skip blockers

            Values v = action.getValues();
            BigDecimal score = BigDecimal.ZERO;

            // Only positive hull/crew values
            if (v.getHullStrength() != null && v.getHullStrength().compareTo(BigDecimal.ZERO) > 0) {
                score = score.add(v.getHullStrength());
            }
            if (v.getCrewHealth() != null && v.getCrewHealth().compareTo(BigDecimal.ZERO) > 0) {
                score = score.add(v.getCrewHealth().multiply(new BigDecimal("1.5")));
            }

            // Penalize negative values
            if (v.getHullStrength() != null && v.getHullStrength().compareTo(BigDecimal.ZERO) < 0) {
                score = score.add(v.getHullStrength());
            }
            if (v.getCrewHealth() != null && v.getCrewHealth().compareTo(BigDecimal.ZERO) < 0) {
                score = score.add(v.getCrewHealth().multiply(new BigDecimal("2")));
            }

            if (score.compareTo(bestScore) > 0) {
                bestScore = score;
                best = action;
            }
        }
        return best;
    }

    private Action findBestRepair(List<Action> actions, Set<Long> used) {
        // Only find actions with positive hull OR crew, no costs
        Action best = null;
        BigDecimal bestScore = BigDecimal.ZERO;

        for (Action action : actions) {
            if (used.contains(action.getId()) || action.getValues() == null) continue;
            if (action.getEffectId() != -1) continue; // Skip blockers

            Values v = action.getValues();

            // Skip if it has any costs
            boolean hasCost = (v.getHullStrength() != null && v.getHullStrength().compareTo(BigDecimal.ZERO) < 0)
                    || (v.getCrewHealth() != null && v.getCrewHealth().compareTo(BigDecimal.ZERO) < 0);

            if (hasCost) continue;

            BigDecimal score = BigDecimal.ZERO;
            if (v.getHullStrength() != null && v.getHullStrength().compareTo(BigDecimal.ZERO) > 0) {
                score = score.add(v.getHullStrength());
            }
            if (v.getCrewHealth() != null && v.getCrewHealth().compareTo(BigDecimal.ZERO) > 0) {
                score = score.add(v.getCrewHealth());
            }

            if (score.compareTo(bestScore) > 0) {
                bestScore = score;
                best = action;
            }
        }
        return best;
    }

    private Values applyValues(Values current, Values delta) {
        if (delta == null) return current;

        Values result = new Values();

        // Apply max values first
        BigDecimal maxHull = current.getMaxHullStrength();
        BigDecimal maxCrew = current.getMaxCrewHealth();

        if (delta.getMaxHullStrength() != null) {
            maxHull = maxHull.add(delta.getMaxHullStrength());
        }
        if (delta.getMaxCrewHealth() != null) {
            maxCrew = maxCrew.add(delta.getMaxCrewHealth());
        }

        maxHull = maxHull.max(BigDecimal.ZERO);
        maxCrew = maxCrew.max(BigDecimal.ZERO);

        result.setMaxHullStrength(maxHull);
        result.setMaxCrewHealth(maxCrew);

        // Apply current values
        BigDecimal hull = current.getHullStrength();
        BigDecimal crew = current.getCrewHealth();

        if (delta.getHullStrength() != null) {
            hull = hull.add(delta.getHullStrength());
        }
        if (delta.getCrewHealth() != null) {
            crew = crew.add(delta.getCrewHealth());
        }

        // Clamp to [0, max]
        hull = hull.min(maxHull).max(BigDecimal.ZERO);
        crew = crew.min(maxCrew).max(BigDecimal.ZERO);

        result.setHullStrength(hull);
        result.setCrewHealth(crew);

        return result;
    }

    private boolean isDead(Values values) {
        return values.getHullStrength().compareTo(BigDecimal.ZERO) <= 0
                || values.getCrewHealth().compareTo(BigDecimal.ZERO) <= 0;
    }

    private boolean isCritical(Values values) {
        BigDecimal hullPercent = values.getHullStrength().divide(
                values.getMaxHullStrength(), 10, BigDecimal.ROUND_HALF_UP);
        BigDecimal crewPercent = values.getCrewHealth().divide(
                values.getMaxCrewHealth(), 10, BigDecimal.ROUND_HALF_UP);

        return hullPercent.compareTo(CRITICAL_HULL_PERCENT) < 0
                || crewPercent.compareTo(CRITICAL_CREW_PERCENT) < 0;
    }

    private boolean isBelowHalf(Values values) {
        BigDecimal half = new BigDecimal("0.50");
        BigDecimal hullPercent = values.getHullStrength().divide(
                values.getMaxHullStrength(), 10, BigDecimal.ROUND_HALF_UP);
        BigDecimal crewPercent = values.getCrewHealth().divide(
                values.getMaxCrewHealth(), 10, BigDecimal.ROUND_HALF_UP);

        return hullPercent.compareTo(half) < 0 || crewPercent.compareTo(half) < 0;
    }
}
