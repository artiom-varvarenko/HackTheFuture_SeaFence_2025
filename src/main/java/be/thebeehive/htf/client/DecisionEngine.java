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

    // Buffer thresholds - adjusted to be more conservative
    private static final BigDecimal HULL_BUFFER_THRESHOLD = new BigDecimal("0.45");
    private static final BigDecimal CREW_BUFFER_THRESHOLD = new BigDecimal("0.50");

    // Critical threshold for extra crew cost penalty
    private static final BigDecimal CREW_CRITICAL_RATIO = new BigDecimal("0.40");

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
     * Enhanced planner with threat prioritization:
     * 1. Pre-analyze ALL threats and sort by severity
     * 2. Block the most severe threats first
     * 3. Ensure total unavoidable damage is survivable before adding beneficial actions
     * 4. Top up to buffers only when safe
     * 5. Append free upgrades if still safe
     */
    private List<Long> greedyEarliestDeathPlanner(Values initialState, List<Action> actions, List<Effect> effects) {
        List<Long> chosen = new ArrayList<>();
        Set<Long> usedActionIds = new HashSet<>();

        // Pre-analyze threats and sort by severity (largest damage first)
        List<ThreatInfo> threats = analyzeThreats(effects);
        traceLog.append(String.format("Analyzed %d threats\n", threats.size()));

        // Phase 1: Block threats in order of severity
        for (ThreatInfo threat : threats) {
            if (chosen.size() >= MAX_ACTIONS_PER_ROUND || isTimeout()) break;

            // Check if we still die with current actions
            SimulationResult result = simulate(initialState, chosen, actions, effects);
            if (result.survived) {
                traceLog.append("Survived with current actions, checking next threat.\n");
                continue;
            }

            // Try to block this threat if it contributes to death
            Action blocker = findBlockerForEffect(threat.effect, actions, usedActionIds);

            if (blocker != null) {
                // Test if blocking this threat helps
                List<Long> testActions = new ArrayList<>(chosen);
                insertActionAtBestPosition(testActions, blocker.getId(), threat.effect.getStep(), actions, effects);
                SimulationResult testResult = simulate(initialState, testActions, actions, effects);

                if (testResult.survived || testResult.deathStep > result.deathStep) {
                    insertActionAtBestPosition(chosen, blocker.getId(), threat.effect.getStep(), actions, effects);
                    usedActionIds.add(blocker.getId());
                    traceLog.append(String.format("Blocked threat %d (severity=%.0f) with action %d\n",
                            threat.effect.getId(), threat.severity, blocker.getId()));
                    continue;
                }
            }
        }

        // Phase 1b: If still dying, try to add heals/repairs to survive
        int maxHealAttempts = 5;
        int healAttempts = 0;
        while (chosen.size() < MAX_ACTIONS_PER_ROUND && !isTimeout() && healAttempts < maxHealAttempts) {
            SimulationResult result = simulate(initialState, chosen, actions, effects);

            if (result.survived) {
                traceLog.append("Survived after adding heals.\n");
                break;
            }

            Action bestHeal = findBestHeal(result, initialState, actions, effects, usedActionIds, chosen);

            if (bestHeal != null) {
                insertActionAtBestPosition(chosen, bestHeal.getId(), result.deathStep, actions, effects);
                usedActionIds.add(bestHeal.getId());
                traceLog.append(String.format("Added heal %d (attempt %d)\n", bestHeal.getId(), healAttempts + 1));
                healAttempts++;
            } else {
                traceLog.append("No more heals available, cannot prevent death.\n");
                break;
            }
        }

        // Phase 2: Top up to buffers only if we're surviving
        SimulationResult finalCheck = simulate(initialState, chosen, actions, effects);
        if (finalCheck.survived) {
            topUpToBuffers(initialState, chosen, actions, effects, usedActionIds);
            appendFreeUpgrades(initialState, chosen, actions, effects, usedActionIds);
        } else {
            traceLog.append("WARNING: Not survived, skipping buffer top-up and upgrades.\n");
        }

        return chosen;
    }

    /**
     * Analyze all threats and sort by severity (largest damage first).
     */
    private List<ThreatInfo> analyzeThreats(List<Effect> effects) {
        List<ThreatInfo> threats = new ArrayList<>();

        for (Effect effect : effects) {
            if (effect.getValues() == null) continue;

            BigDecimal severity = calculateThreatSeverity(effect.getValues());
            if (severity.compareTo(BigDecimal.ZERO) > 0) {
                threats.add(new ThreatInfo(effect, severity));
            }
        }

        // Sort by severity (descending), then by step (ascending)
        threats.sort((a, b) -> {
            int severityCompare = b.severity.compareTo(a.severity);
            if (severityCompare != 0) return severityCompare;
            return Integer.compare(a.effect.getStep(), b.effect.getStep());
        });

        return threats;
    }

    /**
     * Calculate threat severity (total negative impact).
     */
    private BigDecimal calculateThreatSeverity(Values values) {
        BigDecimal severity = BigDecimal.ZERO;

        if (values.getHullStrength() != null && values.getHullStrength().compareTo(BigDecimal.ZERO) < 0) {
            // Hull damage is critical
            severity = severity.add(values.getHullStrength().abs().multiply(new BigDecimal("1.5")));
        }

        if (values.getCrewHealth() != null && values.getCrewHealth().compareTo(BigDecimal.ZERO) < 0) {
            // Crew damage is also critical
            severity = severity.add(values.getCrewHealth().abs().multiply(new BigDecimal("2.0")));
        }

        return severity;
    }

    /**
     * Find a blocker action for a specific effect.
     */
    private Action findBlockerForEffect(Effect effect, List<Action> actions, Set<Long> usedActionIds) {
        Action bestBlocker = null;
        BigDecimal bestScore = new BigDecimal("-999999");

        for (Action action : actions) {
            if (action.getEffectId() == effect.getId() && !usedActionIds.contains(action.getId())) {
                // Prefer blockers with minimal crew cost
                BigDecimal score = BigDecimal.ZERO;

                if (action.getValues() != null) {
                    // Penalize crew costs heavily
                    if (action.getValues().getCrewHealth() != null &&
                        action.getValues().getCrewHealth().compareTo(BigDecimal.ZERO) < 0) {
                        score = score.add(action.getValues().getCrewHealth());
                    }

                    // Reward hull/crew gains
                    if (action.getValues().getHullStrength() != null &&
                        action.getValues().getHullStrength().compareTo(BigDecimal.ZERO) > 0) {
                        score = score.add(action.getValues().getHullStrength().multiply(new BigDecimal("0.5")));
                    }
                }

                if (score.compareTo(bestScore) > 0) {
                    bestScore = score;
                    bestBlocker = action;
                }
            }
        }

        return bestBlocker;
    }

    /**
     * Threat information for prioritization.
     */
    private static class ThreatInfo {
        final Effect effect;
        final BigDecimal severity;

        ThreatInfo(Effect effect, BigDecimal severity) {
            this.effect = effect;
            this.severity = severity;
        }
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
     * Find the best heal action with improved scoring.
     */
    private Action findBestHeal(SimulationResult result, Values initialState, List<Action> actions,
                               List<Effect> effects, Set<Long> usedActionIds, List<Long> currentActions) {
        Action bestHeal = null;
        BigDecimal bestScore = BigDecimal.ZERO;

        // Determine what kind of heal we need based on death state
        boolean needsHullRepair = result.deathState.hullStrength.compareTo(BigDecimal.ZERO) <= 0;
        boolean needsCrewHeal = result.deathState.crewHealth.compareTo(BigDecimal.ZERO) <= 0;

        for (Action action : actions) {
            if (usedActionIds.contains(action.getId())) continue;
            if (action.getValues() == null) continue;
            if (action.getEffectId() != -1) continue; // Skip blockers

            Values v = action.getValues();
            BigDecimal healValue = BigDecimal.ZERO;

            // Prioritize the type of heal we need
            if (needsHullRepair && v.getHullStrength() != null && v.getHullStrength().compareTo(BigDecimal.ZERO) > 0) {
                healValue = healValue.add(v.getHullStrength().multiply(new BigDecimal("2.0")));
            } else if (v.getHullStrength() != null && v.getHullStrength().compareTo(BigDecimal.ZERO) > 0) {
                healValue = healValue.add(v.getHullStrength());
            }

            if (needsCrewHeal && v.getCrewHealth() != null && v.getCrewHealth().compareTo(BigDecimal.ZERO) > 0) {
                healValue = healValue.add(v.getCrewHealth().multiply(new BigDecimal("3.0")));
            } else if (v.getCrewHealth() != null && v.getCrewHealth().compareTo(BigDecimal.ZERO) > 0) {
                healValue = healValue.add(v.getCrewHealth().multiply(new BigDecimal("1.5")));
            }

            // Also value max increases
            if (v.getMaxHullStrength() != null && v.getMaxHullStrength().compareTo(BigDecimal.ZERO) > 0) {
                healValue = healValue.add(v.getMaxHullStrength().multiply(new BigDecimal("0.3")));
            }

            // Penalize costs more severely
            if (v.getHullStrength() != null && v.getHullStrength().compareTo(BigDecimal.ZERO) < 0) {
                healValue = healValue.add(v.getHullStrength().multiply(new BigDecimal("1.5")));
            }
            if (v.getCrewHealth() != null && v.getCrewHealth().compareTo(BigDecimal.ZERO) < 0) {
                healValue = healValue.add(v.getCrewHealth().multiply(new BigDecimal("2.0")));
            }

            if (healValue.compareTo(bestScore) > 0) {
                // Test if this heal saves us or delays death
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
