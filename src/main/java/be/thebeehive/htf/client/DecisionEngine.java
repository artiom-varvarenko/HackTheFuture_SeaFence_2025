package be.thebeehive.htf.client;

import be.thebeehive.htf.library.protocol.server.GameRoundServerMessage;
import be.thebeehive.htf.library.protocol.server.GameRoundServerMessage.Action;
import be.thebeehive.htf.library.protocol.server.GameRoundServerMessage.Effect;
import be.thebeehive.htf.library.protocol.server.GameRoundServerMessage.Values;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * DecisionEngine for submarine game client.
 * Uses stepwise simulation and greedy earliest-death planning to survive rounds.
 */
public class DecisionEngine {

    private static final int MAX_ACTIONS_PER_ROUND = 4;
    private static final long MAX_DECISION_TIME_MS = 300;

    // Buffer thresholds
    private static final BigDecimal HULL_BUFFER_THRESHOLD = new BigDecimal("0.35");
    private static final BigDecimal CREW_BUFFER_THRESHOLD = new BigDecimal("0.40");

    // Critical threshold for extra crew cost penalty
    private static final BigDecimal CREW_CRITICAL_RATIO = new BigDecimal("0.35");

    private final long startTime;
    private final StringBuilder traceLog;

    public DecisionEngine() {
        this.startTime = System.currentTimeMillis();
        this.traceLog = new StringBuilder();
    }

    /**
     * Main entry point: plan actions for the round.
     */
    public List<Long> planActions(GameRoundServerMessage msg) {
        Values initialState = msg.getOurSubmarine().getValues();
        List<Action> actions = msg.getActions();
        List<Effect> effects = msg.getEffects();

        traceLog.append(String.format("Round %d | Hull: %s/%s | Crew: %s/%s\n",
                msg.getRound(),
                initialState.getHullStrength(),
                initialState.getMaxHullStrength(),
                initialState.getCrewHealth(),
                initialState.getMaxCrewHealth()));

        List<Long> chosen = greedyEarliestDeathPlanner(initialState, actions, effects);

        traceLog.append(String.format("Chosen %d actions: %s\n", chosen.size(), chosen));
        return chosen;
    }

    /**
     * Get the trace log for this decision.
     */
    public String getTraceLog() {
        return traceLog.toString();
    }

    /**
     * Greedy earliest-death planner:
     * 1. Simulate with no actions
     * 2. If death at step s, insert best blocker for step-s effect at/before s
     * 3. If no blocker saves, insert best heal at/before s
     * 4. When safe, top up to buffers
     * 5. Append free upgrades if still safe
     */
    private List<Long> greedyEarliestDeathPlanner(Values initialState, List<Action> actions, List<Effect> effects) {
        List<Long> chosen = new ArrayList<>();
        Set<Long> usedActionIds = new HashSet<>();

        // Phase 1: Ensure survival by blocking/healing
        while (chosen.size() < MAX_ACTIONS_PER_ROUND && !isTimeout()) {
            SimulationResult result = simulate(initialState, chosen, actions, effects);

            if (result.survived) {
                traceLog.append("Simulation survived all steps.\n");
                break;
            }

            traceLog.append(String.format("Death at step %d | Hull: %s | Crew: %s\n",
                    result.deathStep, result.deathState.hullStrength, result.deathState.crewHealth));

            // Try to find a blocker for the deadly effect at deathStep
            Action bestBlocker = findBestBlocker(result, initialState, actions, effects, usedActionIds, chosen);

            if (bestBlocker != null) {
                insertActionAtBestPosition(chosen, bestBlocker.getId(), result.deathStep, actions, effects);
                usedActionIds.add(bestBlocker.getId());
                traceLog.append(String.format("Inserted blocker %d\n", bestBlocker.getId()));
                continue;
            }

            // No blocker found, try to insert best heal
            Action bestHeal = findBestHeal(result, initialState, actions, effects, usedActionIds, chosen);

            if (bestHeal != null) {
                insertActionAtBestPosition(chosen, bestHeal.getId(), result.deathStep, actions, effects);
                usedActionIds.add(bestHeal.getId());
                traceLog.append(String.format("Inserted heal %d\n", bestHeal.getId()));
                continue;
            }

            // Cannot save ourselves
            traceLog.append("Cannot prevent death, returning current actions.\n");
            break;
        }

        // Phase 2: Top up to buffers
        topUpToBuffers(initialState, chosen, actions, effects, usedActionIds);

        // Phase 3: Append free upgrades
        appendFreeUpgrades(initialState, chosen, actions, effects, usedActionIds);

        return chosen;
    }

    /**
     * Simulate the round with the given actions.
     */
    private SimulationResult simulate(Values initialState, List<Long> actionIds, List<Action> actions, List<Effect> effects) {
        Map<Long, Action> actionMap = actions.stream().collect(Collectors.toMap(Action::getId, a -> a));
        Map<Integer, List<Effect>> effectsByStep = effects.stream()
                .collect(Collectors.groupingBy(Effect::getStep));

        SimulationState state = new SimulationState(initialState);
        Set<Long> blockedEffectIds = new HashSet<>();

        // Determine maximum step
        int maxStep = Math.max(
                actionIds.size(),
                effectsByStep.keySet().stream().max(Integer::compareTo).orElse(0)
        );

        for (int step = 1; step <= maxStep; step++) {
            // Step s: Apply our action (if we have one)
            if (step <= actionIds.size()) {
                Long actionId = actionIds.get(step - 1);
                Action action = actionMap.get(actionId);
                if (action != null) {
                    state.applyValues(action.getValues());
                    if (action.getEffectId() != -1) {
                        blockedEffectIds.add(action.getEffectId());
                    }

                    if (state.isDead()) {
                        return new SimulationResult(false, step, state.copy());
                    }
                }
            }

            // Apply all effects at step s (unless blocked)
            List<Effect> stepEffects = effectsByStep.get(step);
            if (stepEffects != null) {
                for (Effect effect : stepEffects) {
                    if (!blockedEffectIds.contains(effect.getId())) {
                        state.applyValues(effect.getValues());
                        if (state.isDead()) {
                            return new SimulationResult(false, step, state.copy());
                        }
                    }
                }
            }
        }

        return new SimulationResult(true, -1, state.copy());
    }

    /**
     * Find the best blocker for the effect causing death at deathStep.
     */
    private Action findBestBlocker(SimulationResult result, Values initialState, List<Action> actions,
                                   List<Effect> effects, Set<Long> usedActionIds, List<Long> currentActions) {
        // Find effects at deathStep
        List<Effect> deathStepEffects = effects.stream()
                .filter(e -> e.getStep() == result.deathStep)
                .collect(Collectors.toList());

        Action bestBlocker = null;
        BigDecimal bestScore = new BigDecimal("-999999");

        for (Effect effect : deathStepEffects) {
            // Find actions that can block this effect
            for (Action action : actions) {
                if (action.getEffectId() == effect.getId() && !usedActionIds.contains(action.getId())) {
                    // Score this blocker
                    BigDecimal score = scoreBlocker(action, effect, initialState);

                    // Check if inserting this action keeps us safe
                    List<Long> testActions = new ArrayList<>(currentActions);
                    insertActionAtBestPosition(testActions, action.getId(), result.deathStep, actions, effects);

                    SimulationResult testResult = simulate(initialState, testActions, actions, effects);

                    if (testResult.survived || testResult.deathStep > result.deathStep) {
                        if (score.compareTo(bestScore) > 0) {
                            bestScore = score;
                            bestBlocker = action;
                        }
                    }
                }
            }
        }

        return bestBlocker;
    }

    /**
     * Score a blocker: prevented damage + action benefit - action cost,
     * with extra penalty for crew costs when crew/maxCrew < 0.35.
     */
    private BigDecimal scoreBlocker(Action action, Effect effect, Values initialState) {
        Values actionValues = action.getValues();
        Values effectValues = effect.getValues();

        BigDecimal preventedDamage = BigDecimal.ZERO;
        if (effectValues.getHullStrength() != null && effectValues.getHullStrength().compareTo(BigDecimal.ZERO) < 0) {
            preventedDamage = preventedDamage.add(effectValues.getHullStrength().abs());
        }
        if (effectValues.getCrewHealth() != null && effectValues.getCrewHealth().compareTo(BigDecimal.ZERO) < 0) {
            preventedDamage = preventedDamage.add(effectValues.getCrewHealth().abs());
        }

        BigDecimal actionBenefit = BigDecimal.ZERO;
        if (actionValues.getHullStrength() != null && actionValues.getHullStrength().compareTo(BigDecimal.ZERO) > 0) {
            actionBenefit = actionBenefit.add(actionValues.getHullStrength());
        }
        if (actionValues.getCrewHealth() != null && actionValues.getCrewHealth().compareTo(BigDecimal.ZERO) > 0) {
            actionBenefit = actionBenefit.add(actionValues.getCrewHealth());
        }
        if (actionValues.getMaxHullStrength() != null && actionValues.getMaxHullStrength().compareTo(BigDecimal.ZERO) > 0) {
            actionBenefit = actionBenefit.add(actionValues.getMaxHullStrength().multiply(new BigDecimal("0.1")));
        }
        if (actionValues.getMaxCrewHealth() != null && actionValues.getMaxCrewHealth().compareTo(BigDecimal.ZERO) > 0) {
            actionBenefit = actionBenefit.add(actionValues.getMaxCrewHealth().multiply(new BigDecimal("0.1")));
        }

        BigDecimal actionCost = BigDecimal.ZERO;
        if (actionValues.getHullStrength() != null && actionValues.getHullStrength().compareTo(BigDecimal.ZERO) < 0) {
            actionCost = actionCost.add(actionValues.getHullStrength().abs());
        }
        if (actionValues.getCrewHealth() != null && actionValues.getCrewHealth().compareTo(BigDecimal.ZERO) < 0) {
            BigDecimal crewCost = actionValues.getCrewHealth().abs();

            // Extra penalty for crew costs when crew/maxCrew < 0.35
            BigDecimal crewRatio = initialState.getCrewHealth().divide(
                    initialState.getMaxCrewHealth(), 10, BigDecimal.ROUND_HALF_UP);

            if (crewRatio.compareTo(CREW_CRITICAL_RATIO) < 0) {
                crewCost = crewCost.multiply(new BigDecimal("2.0"));
            }

            actionCost = actionCost.add(crewCost);
        }

        return preventedDamage.add(actionBenefit).subtract(actionCost);
    }

    /**
     * Find the best heal action.
     */
    private Action findBestHeal(SimulationResult result, Values initialState, List<Action> actions,
                               List<Effect> effects, Set<Long> usedActionIds, List<Long> currentActions) {
        Action bestHeal = null;
        BigDecimal bestScore = BigDecimal.ZERO;

        for (Action action : actions) {
            if (usedActionIds.contains(action.getId())) continue;
            if (action.getValues() == null) continue;

            Values v = action.getValues();
            BigDecimal healValue = BigDecimal.ZERO;

            if (v.getHullStrength() != null && v.getHullStrength().compareTo(BigDecimal.ZERO) > 0) {
                healValue = healValue.add(v.getHullStrength());
            }
            if (v.getCrewHealth() != null && v.getCrewHealth().compareTo(BigDecimal.ZERO) > 0) {
                healValue = healValue.add(v.getCrewHealth().multiply(new BigDecimal("1.5")));
            }

            // Penalize if action has costs
            if (v.getHullStrength() != null && v.getHullStrength().compareTo(BigDecimal.ZERO) < 0) {
                healValue = healValue.add(v.getHullStrength());
            }
            if (v.getCrewHealth() != null && v.getCrewHealth().compareTo(BigDecimal.ZERO) < 0) {
                healValue = healValue.add(v.getCrewHealth());
            }

            if (healValue.compareTo(bestScore) > 0) {
                // Test if this heal saves us
                List<Long> testActions = new ArrayList<>(currentActions);
                insertActionAtBestPosition(testActions, action.getId(), result.deathStep, actions, effects);

                SimulationResult testResult = simulate(initialState, testActions, actions, effects);

                if (testResult.survived || testResult.deathStep > result.deathStep) {
                    bestScore = healValue;
                    bestHeal = action;
                }
            }
        }

        return bestHeal;
    }

    /**
     * Insert action at best position (at or before deathStep).
     */
    private void insertActionAtBestPosition(List<Long> actions, Long actionId, int deathStep,
                                           List<Action> allActions, List<Effect> effects) {
        // Try to insert as early as possible but at or before deathStep
        int targetPosition = Math.min(actions.size(), Math.max(0, deathStep - 1));

        if (targetPosition >= actions.size()) {
            actions.add(actionId);
        } else {
            actions.add(targetPosition, actionId);
        }

        // Ensure we don't exceed max actions
        while (actions.size() > MAX_ACTIONS_PER_ROUND) {
            actions.remove(actions.size() - 1);
        }
    }

    /**
     * Top up to buffers: hull ≥ 35% max, crew ≥ 40% max.
     */
    private void topUpToBuffers(Values initialState, List<Long> chosen, List<Action> actions,
                               List<Effect> effects, Set<Long> usedActionIds) {
        while (chosen.size() < MAX_ACTIONS_PER_ROUND && !isTimeout()) {
            SimulationResult result = simulate(initialState, chosen, actions, effects);
            if (!result.survived) break;

            SimulationState endState = result.deathState;
            BigDecimal hullRatio = endState.hullStrength.divide(endState.maxHullStrength, 10, BigDecimal.ROUND_HALF_UP);
            BigDecimal crewRatio = endState.crewHealth.divide(endState.maxCrewHealth, 10, BigDecimal.ROUND_HALF_UP);

            boolean needsHullRepair = hullRatio.compareTo(HULL_BUFFER_THRESHOLD) < 0;
            boolean needsCrewHeal = crewRatio.compareTo(CREW_BUFFER_THRESHOLD) < 0;

            if (!needsHullRepair && !needsCrewHeal) {
                break;
            }

            Action bestRepair = findBestRepair(needsHullRepair, needsCrewHeal, actions, usedActionIds, initialState, chosen, effects);
            if (bestRepair != null) {
                chosen.add(bestRepair.getId());
                usedActionIds.add(bestRepair.getId());
                traceLog.append(String.format("Buffer top-up: added action %d\n", bestRepair.getId()));
            } else {
                break;
            }
        }
    }

    /**
     * Find best repair action for buffer top-up.
     */
    private Action findBestRepair(boolean needsHull, boolean needsCrew, List<Action> actions,
                                  Set<Long> usedActionIds, Values initialState, List<Long> currentActions,
                                  List<Effect> effects) {
        Action best = null;
        BigDecimal bestScore = BigDecimal.ZERO;

        for (Action action : actions) {
            if (usedActionIds.contains(action.getId())) continue;
            if (action.getValues() == null) continue;
            if (action.getEffectId() != -1) continue; // Skip blockers

            Values v = action.getValues();
            BigDecimal score = BigDecimal.ZERO;

            if (needsHull && v.getHullStrength() != null && v.getHullStrength().compareTo(BigDecimal.ZERO) > 0) {
                score = score.add(v.getHullStrength());
            }
            if (needsCrew && v.getCrewHealth() != null && v.getCrewHealth().compareTo(BigDecimal.ZERO) > 0) {
                score = score.add(v.getCrewHealth().multiply(new BigDecimal("1.2")));
            }

            // Penalize costs
            if (v.getHullStrength() != null && v.getHullStrength().compareTo(BigDecimal.ZERO) < 0) {
                score = score.add(v.getHullStrength());
            }
            if (v.getCrewHealth() != null && v.getCrewHealth().compareTo(BigDecimal.ZERO) < 0) {
                score = score.add(v.getCrewHealth());
            }

            if (score.compareTo(bestScore) > 0) {
                // Ensure adding this action keeps us safe
                List<Long> testActions = new ArrayList<>(currentActions);
                testActions.add(action.getId());
                SimulationResult testResult = simulate(initialState, testActions, actions, effects);

                if (testResult.survived) {
                    bestScore = score;
                    best = action;
                }
            }
        }

        return best;
    }

    /**
     * Append free upgrades (max* increases with no immediate cost).
     */
    private void appendFreeUpgrades(Values initialState, List<Long> chosen, List<Action> actions,
                                   List<Effect> effects, Set<Long> usedActionIds) {
        while (chosen.size() < MAX_ACTIONS_PER_ROUND && !isTimeout()) {
            Action bestUpgrade = null;
            BigDecimal bestScore = BigDecimal.ZERO;

            for (Action action : actions) {
                if (usedActionIds.contains(action.getId())) continue;
                if (action.getValues() == null) continue;
                if (action.getEffectId() != -1) continue; // Skip blockers

                Values v = action.getValues();

                // Check if it's a free upgrade
                boolean hasCost = (v.getHullStrength() != null && v.getHullStrength().compareTo(BigDecimal.ZERO) < 0)
                        || (v.getCrewHealth() != null && v.getCrewHealth().compareTo(BigDecimal.ZERO) < 0);

                if (hasCost) continue;

                BigDecimal score = BigDecimal.ZERO;
                if (v.getMaxHullStrength() != null && v.getMaxHullStrength().compareTo(BigDecimal.ZERO) > 0) {
                    score = score.add(v.getMaxHullStrength());
                }
                if (v.getMaxCrewHealth() != null && v.getMaxCrewHealth().compareTo(BigDecimal.ZERO) > 0) {
                    score = score.add(v.getMaxCrewHealth());
                }

                if (score.compareTo(bestScore) > 0) {
                    // Ensure adding this action keeps us safe
                    List<Long> testActions = new ArrayList<>(chosen);
                    testActions.add(action.getId());
                    SimulationResult testResult = simulate(initialState, testActions, actions, effects);

                    if (testResult.survived) {
                        bestScore = score;
                        bestUpgrade = action;
                    }
                }
            }

            if (bestUpgrade != null) {
                chosen.add(bestUpgrade.getId());
                usedActionIds.add(bestUpgrade.getId());
                traceLog.append(String.format("Free upgrade: added action %d\n", bestUpgrade.getId()));
            } else {
                break;
            }
        }
    }

    private boolean isTimeout() {
        return System.currentTimeMillis() - startTime > MAX_DECISION_TIME_MS;
    }

    /**
     * Simulation state with BigDecimal arithmetic and clamping.
     */
    private static class SimulationState {
        BigDecimal hullStrength;
        BigDecimal maxHullStrength;
        BigDecimal crewHealth;
        BigDecimal maxCrewHealth;

        SimulationState(Values initial) {
            this.hullStrength = initial.getHullStrength();
            this.maxHullStrength = initial.getMaxHullStrength();
            this.crewHealth = initial.getCrewHealth();
            this.maxCrewHealth = initial.getMaxCrewHealth();
        }

        SimulationState(BigDecimal hull, BigDecimal maxHull, BigDecimal crew, BigDecimal maxCrew) {
            this.hullStrength = hull;
            this.maxHullStrength = maxHull;
            this.crewHealth = crew;
            this.maxCrewHealth = maxCrew;
        }

        void applyValues(Values delta) {
            if (delta == null) return;

            // Update maximums first
            if (delta.getMaxHullStrength() != null) {
                maxHullStrength = maxHullStrength.add(delta.getMaxHullStrength());
                maxHullStrength = clampToZero(maxHullStrength);
            }
            if (delta.getMaxCrewHealth() != null) {
                maxCrewHealth = maxCrewHealth.add(delta.getMaxCrewHealth());
                maxCrewHealth = clampToZero(maxCrewHealth);
            }

            // Update current values
            if (delta.getHullStrength() != null) {
                hullStrength = hullStrength.add(delta.getHullStrength());
            }
            if (delta.getCrewHealth() != null) {
                crewHealth = crewHealth.add(delta.getCrewHealth());
            }

            // Clamp to [0, max]
            hullStrength = clamp(hullStrength, BigDecimal.ZERO, maxHullStrength);
            crewHealth = clamp(crewHealth, BigDecimal.ZERO, maxCrewHealth);
        }

        boolean isDead() {
            return hullStrength.compareTo(BigDecimal.ZERO) <= 0
                || crewHealth.compareTo(BigDecimal.ZERO) <= 0;
        }

        SimulationState copy() {
            return new SimulationState(hullStrength, maxHullStrength, crewHealth, maxCrewHealth);
        }

        private BigDecimal clamp(BigDecimal value, BigDecimal min, BigDecimal max) {
            if (value.compareTo(min) < 0) return min;
            if (value.compareTo(max) > 0) return max;
            return value;
        }

        private BigDecimal clampToZero(BigDecimal value) {
            return value.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : value;
        }
    }

    /**
     * Result of a simulation.
     */
    private static class SimulationResult {
        final boolean survived;
        final int deathStep;
        final SimulationState deathState;

        SimulationResult(boolean survived, int deathStep, SimulationState deathState) {
            this.survived = survived;
            this.deathStep = deathStep;
            this.deathState = deathState;
        }
    }
}
