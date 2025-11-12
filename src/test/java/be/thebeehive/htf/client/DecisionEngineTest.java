package be.thebeehive.htf.client;

import be.thebeehive.htf.library.protocol.server.GameRoundServerMessage;
import be.thebeehive.htf.library.protocol.server.GameRoundServerMessage.Action;
import be.thebeehive.htf.library.protocol.server.GameRoundServerMessage.Effect;
import be.thebeehive.htf.library.protocol.server.GameRoundServerMessage.Submarine;
import be.thebeehive.htf.library.protocol.server.GameRoundServerMessage.Values;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.Assert.*;

/**
 * Unit tests for DecisionEngine with four test fixtures.
 */
public class DecisionEngineTest {

    /**
     * Fixture 1: Simple survival with no threats.
     * Should survive without any blockers, and apply beneficial actions.
     */
    @Test
    public void testFixture1_SimpleSurvival() {
        GameRoundServerMessage msg = createFixture1();
        DecisionEngine engine = new DecisionEngine();

        List<Long> actions = engine.planActions(msg);

        assertNotNull("Actions should not be null", actions);
        assertTrue("Should choose some actions", actions.size() > 0);
        assertTrue("Should not exceed max actions", actions.size() <= 4);

        // Verify survival
        assertTrue("Should survive with chosen actions",
                simulateAndCheckSurvival(msg, actions));

        System.out.println("Fixture 1 Trace:\n" + engine.getTraceLog());
    }

    /**
     * Fixture 2: Death scenario with blocker available.
     * Without action, submarine dies at step 2 due to torpedo.
     * Should use blocker action to prevent death.
     */
    @Test
    public void testFixture2_BlockerPreventsDeathFromTorpedo() {
        GameRoundServerMessage msg = createFixture2();
        DecisionEngine engine = new DecisionEngine();

        List<Long> actions = engine.planActions(msg);

        assertNotNull("Actions should not be null", actions);
        assertTrue("Should choose at least one action to block", actions.size() > 0);

        // The blocker action (id=102) should be in the list
        assertTrue("Should include blocker action",
                actions.contains(102L));

        // Verify survival
        assertTrue("Should survive with blocker",
                simulateAndCheckSurvival(msg, actions));

        System.out.println("Fixture 2 Trace:\n" + engine.getTraceLog());
    }

    /**
     * Fixture 3: Death scenario with heal needed.
     * Submarine takes unavoidable damage and needs healing to survive.
     * Should insert heal action to prevent death.
     */
    @Test
    public void testFixture3_HealPreventsDeathFromDamage() {
        GameRoundServerMessage msg = createFixture3();
        DecisionEngine engine = new DecisionEngine();

        List<Long> actions = engine.planActions(msg);

        assertNotNull("Actions should not be null", actions);
        assertTrue("Should choose actions", actions.size() > 0);

        // Should include a healing action
        boolean hasHeal = actions.stream().anyMatch(id -> {
            return msg.getActions().stream()
                    .filter(a -> a.getId() == id)
                    .anyMatch(a -> a.getValues() != null &&
                            a.getValues().getCrewHealth() != null &&
                            a.getValues().getCrewHealth().compareTo(BigDecimal.ZERO) > 0);
        });

        assertTrue("Should include healing action", hasHeal);

        // Verify survival
        assertTrue("Should survive with heal",
                simulateAndCheckSurvival(msg, actions));

        System.out.println("Fixture 3 Trace:\n" + engine.getTraceLog());
    }

    /**
     * Fixture 4: Buffer top-up and free upgrades.
     * Already safe from immediate threats, but hull/crew below buffer thresholds.
     * Should top up to buffers and add free upgrades.
     */
    @Test
    public void testFixture4_BufferTopUpAndFreeUpgrades() {
        GameRoundServerMessage msg = createFixture4();
        DecisionEngine engine = new DecisionEngine();

        List<Long> actions = engine.planActions(msg);

        assertNotNull("Actions should not be null", actions);

        // Should choose multiple actions to optimize
        assertTrue("Should choose actions for buffer/upgrades", actions.size() > 0);

        // Verify survival
        assertTrue("Should survive",
                simulateAndCheckSurvival(msg, actions));

        // Check that we've improved our state
        Values finalState = simulateFinalState(msg, actions);
        Values initialState = msg.getOurSubmarine().getValues();

        assertTrue("Final hull should be at least as good as initial",
                finalState.getHullStrength().compareTo(initialState.getHullStrength()) >= 0);
        assertTrue("Final crew should be at least as good as initial",
                finalState.getCrewHealth().compareTo(initialState.getCrewHealth()) >= 0);

        System.out.println("Fixture 4 Trace:\n" + engine.getTraceLog());
    }

    // ========== Test Fixtures ==========

    /**
     * Fixture 1: Simple survival scenario.
     * - Hull: 80000/100000
     * - Crew: 800/1000
     * - No threats
     * - Available: repair (+10000 hull), heal (+100 crew), upgrade (+10000 max hull)
     */
    private GameRoundServerMessage createFixture1() {
        GameRoundServerMessage msg = new GameRoundServerMessage();
        msg.setRound(1);
        msg.setRoundId(UUID.randomUUID());

        // Submarine state
        Values subValues = new Values();
        subValues.setHullStrength(new BigDecimal("80000"));
        subValues.setMaxHullStrength(new BigDecimal("100000"));
        subValues.setCrewHealth(new BigDecimal("800"));
        subValues.setMaxCrewHealth(new BigDecimal("1000"));

        Submarine sub = new Submarine();
        sub.setName("TestSub");
        sub.setValues(subValues);
        sub.setAlive(true);
        msg.setOurSubmarine(sub);

        // No effects (no threats)
        msg.setEffects(new ArrayList<>());

        // Actions
        List<Action> actions = new ArrayList<>();

        // Repair action
        Action repair = new Action();
        repair.setId(101);
        repair.setEffectId(-1);
        Values repairValues = new Values();
        repairValues.setHullStrength(new BigDecimal("10000"));
        repairValues.setMaxHullStrength(BigDecimal.ZERO);
        repairValues.setCrewHealth(BigDecimal.ZERO);
        repairValues.setMaxCrewHealth(BigDecimal.ZERO);
        repair.setValues(repairValues);
        actions.add(repair);

        // Heal action
        Action heal = new Action();
        heal.setId(102);
        heal.setEffectId(-1);
        Values healValues = new Values();
        healValues.setHullStrength(BigDecimal.ZERO);
        healValues.setMaxHullStrength(BigDecimal.ZERO);
        healValues.setCrewHealth(new BigDecimal("100"));
        healValues.setMaxCrewHealth(BigDecimal.ZERO);
        heal.setValues(healValues);
        actions.add(heal);

        // Upgrade action (free)
        Action upgrade = new Action();
        upgrade.setId(103);
        upgrade.setEffectId(-1);
        Values upgradeValues = new Values();
        upgradeValues.setHullStrength(BigDecimal.ZERO);
        upgradeValues.setMaxHullStrength(new BigDecimal("10000"));
        upgradeValues.setCrewHealth(BigDecimal.ZERO);
        upgradeValues.setMaxCrewHealth(BigDecimal.ZERO);
        upgrade.setValues(upgradeValues);
        actions.add(upgrade);

        msg.setActions(actions);

        return msg;
    }

    /**
     * Fixture 2: Blocker prevents death.
     * - Hull: 30000/100000 (low)
     * - Crew: 500/1000
     * - Effect at step 2: torpedo (-35000 hull)
     * - Blocker available (id=102, blocks effect 201)
     */
    private GameRoundServerMessage createFixture2() {
        GameRoundServerMessage msg = new GameRoundServerMessage();
        msg.setRound(2);
        msg.setRoundId(UUID.randomUUID());

        // Submarine state (low hull)
        Values subValues = new Values();
        subValues.setHullStrength(new BigDecimal("30000"));
        subValues.setMaxHullStrength(new BigDecimal("100000"));
        subValues.setCrewHealth(new BigDecimal("500"));
        subValues.setMaxCrewHealth(new BigDecimal("1000"));

        Submarine sub = new Submarine();
        sub.setName("TestSub");
        sub.setValues(subValues);
        sub.setAlive(true);
        msg.setOurSubmarine(sub);

        // Effect: torpedo at step 2
        Effect torpedo = new Effect();
        torpedo.setId(201);
        torpedo.setStep(2);
        Values torpedoValues = new Values();
        torpedoValues.setHullStrength(new BigDecimal("-35000")); // Would kill us!
        torpedoValues.setMaxHullStrength(BigDecimal.ZERO);
        torpedoValues.setCrewHealth(BigDecimal.ZERO);
        torpedoValues.setMaxCrewHealth(BigDecimal.ZERO);
        torpedo.setValues(torpedoValues);
        msg.setEffects(Arrays.asList(torpedo));

        // Actions
        List<Action> actions = new ArrayList<>();

        // Repair action (not enough to save us)
        Action repair = new Action();
        repair.setId(101);
        repair.setEffectId(-1);
        Values repairValues = new Values();
        repairValues.setHullStrength(new BigDecimal("10000"));
        repairValues.setMaxHullStrength(BigDecimal.ZERO);
        repairValues.setCrewHealth(BigDecimal.ZERO);
        repairValues.setMaxCrewHealth(BigDecimal.ZERO);
        repair.setValues(repairValues);
        actions.add(repair);

        // Blocker action (blocks torpedo)
        Action blocker = new Action();
        blocker.setId(102);
        blocker.setEffectId(201); // Blocks effect 201
        Values blockerValues = new Values();
        blockerValues.setHullStrength(new BigDecimal("-5000")); // Small cost
        blockerValues.setMaxHullStrength(BigDecimal.ZERO);
        blockerValues.setCrewHealth(BigDecimal.ZERO);
        blockerValues.setMaxCrewHealth(BigDecimal.ZERO);
        blocker.setValues(blockerValues);
        actions.add(blocker);

        msg.setActions(actions);

        return msg;
    }

    /**
     * Fixture 3: Heal prevents death.
     * - Hull: 50000/100000
     * - Crew: 250/1000 (low, below 35% threshold)
     * - Effect at step 1: crew injury (-200 crew)
     * - Effect at step 3: another injury (-100 crew)
     * - No blocker, but heal action available
     */
    private GameRoundServerMessage createFixture3() {
        GameRoundServerMessage msg = new GameRoundServerMessage();
        msg.setRound(3);
        msg.setRoundId(UUID.randomUUID());

        // Submarine state (low crew)
        Values subValues = new Values();
        subValues.setHullStrength(new BigDecimal("50000"));
        subValues.setMaxHullStrength(new BigDecimal("100000"));
        subValues.setCrewHealth(new BigDecimal("250")); // Below 35% threshold
        subValues.setMaxCrewHealth(new BigDecimal("1000"));

        Submarine sub = new Submarine();
        sub.setName("TestSub");
        sub.setValues(subValues);
        sub.setAlive(true);
        msg.setOurSubmarine(sub);

        // Effects
        List<Effect> effects = new ArrayList<>();

        Effect injury1 = new Effect();
        injury1.setId(301);
        injury1.setStep(1);
        Values injury1Values = new Values();
        injury1Values.setHullStrength(BigDecimal.ZERO);
        injury1Values.setMaxHullStrength(BigDecimal.ZERO);
        injury1Values.setCrewHealth(new BigDecimal("-200")); // 250 - 200 = 50
        injury1Values.setMaxCrewHealth(BigDecimal.ZERO);
        injury1.setValues(injury1Values);
        effects.add(injury1);

        Effect injury2 = new Effect();
        injury2.setId(302);
        injury2.setStep(3);
        Values injury2Values = new Values();
        injury2Values.setHullStrength(BigDecimal.ZERO);
        injury2Values.setMaxHullStrength(BigDecimal.ZERO);
        injury2Values.setCrewHealth(new BigDecimal("-100")); // Would kill us: 50 - 100 < 0
        injury2Values.setMaxCrewHealth(BigDecimal.ZERO);
        injury2.setValues(injury2Values);
        effects.add(injury2);

        msg.setEffects(effects);

        // Actions (no blockers, just heal)
        List<Action> actions = new ArrayList<>();

        // Heal action
        Action heal = new Action();
        heal.setId(103);
        heal.setEffectId(-1);
        Values healValues = new Values();
        healValues.setHullStrength(BigDecimal.ZERO);
        healValues.setMaxHullStrength(BigDecimal.ZERO);
        healValues.setCrewHealth(new BigDecimal("300")); // Should save us
        healValues.setMaxCrewHealth(BigDecimal.ZERO);
        heal.setValues(healValues);
        actions.add(heal);

        // Another heal
        Action heal2 = new Action();
        heal2.setId(104);
        heal2.setEffectId(-1);
        Values heal2Values = new Values();
        heal2Values.setHullStrength(BigDecimal.ZERO);
        heal2Values.setMaxHullStrength(BigDecimal.ZERO);
        heal2Values.setCrewHealth(new BigDecimal("200"));
        heal2Values.setMaxCrewHealth(BigDecimal.ZERO);
        heal2.setValues(heal2Values);
        actions.add(heal2);

        msg.setActions(actions);

        return msg;
    }

    /**
     * Fixture 4: Buffer top-up and free upgrades.
     * - Hull: 30000/100000 (30%, below 35% buffer)
     * - Crew: 350/1000 (35%, below 40% buffer)
     * - No immediate threats
     * - Repair, heal, and free upgrade actions available
     */
    private GameRoundServerMessage createFixture4() {
        GameRoundServerMessage msg = new GameRoundServerMessage();
        msg.setRound(4);
        msg.setRoundId(UUID.randomUUID());

        // Submarine state (below buffers but safe)
        Values subValues = new Values();
        subValues.setHullStrength(new BigDecimal("30000")); // 30% of max
        subValues.setMaxHullStrength(new BigDecimal("100000"));
        subValues.setCrewHealth(new BigDecimal("350")); // 35% of max
        subValues.setMaxCrewHealth(new BigDecimal("1000"));

        Submarine sub = new Submarine();
        sub.setName("TestSub");
        sub.setValues(subValues);
        sub.setAlive(true);
        msg.setOurSubmarine(sub);

        // No effects
        msg.setEffects(new ArrayList<>());

        // Actions
        List<Action> actions = new ArrayList<>();

        // Repair action
        Action repair = new Action();
        repair.setId(401);
        repair.setEffectId(-1);
        Values repairValues = new Values();
        repairValues.setHullStrength(new BigDecimal("15000"));
        repairValues.setMaxHullStrength(BigDecimal.ZERO);
        repairValues.setCrewHealth(BigDecimal.ZERO);
        repairValues.setMaxCrewHealth(BigDecimal.ZERO);
        repair.setValues(repairValues);
        actions.add(repair);

        // Heal action
        Action heal = new Action();
        heal.setId(402);
        heal.setEffectId(-1);
        Values healValues = new Values();
        healValues.setHullStrength(BigDecimal.ZERO);
        healValues.setMaxHullStrength(BigDecimal.ZERO);
        healValues.setCrewHealth(new BigDecimal("150"));
        healValues.setMaxCrewHealth(BigDecimal.ZERO);
        heal.setValues(healValues);
        actions.add(heal);

        // Free upgrade (max hull)
        Action upgradeHull = new Action();
        upgradeHull.setId(403);
        upgradeHull.setEffectId(-1);
        Values upgradeHullValues = new Values();
        upgradeHullValues.setHullStrength(BigDecimal.ZERO);
        upgradeHullValues.setMaxHullStrength(new BigDecimal("20000"));
        upgradeHullValues.setCrewHealth(BigDecimal.ZERO);
        upgradeHullValues.setMaxCrewHealth(BigDecimal.ZERO);
        upgradeHull.setValues(upgradeHullValues);
        actions.add(upgradeHull);

        // Free upgrade (max crew)
        Action upgradeCrew = new Action();
        upgradeCrew.setId(404);
        upgradeCrew.setEffectId(-1);
        Values upgradeCrewValues = new Values();
        upgradeCrewValues.setHullStrength(BigDecimal.ZERO);
        upgradeCrewValues.setMaxHullStrength(BigDecimal.ZERO);
        upgradeCrewValues.setCrewHealth(BigDecimal.ZERO);
        upgradeCrewValues.setMaxCrewHealth(new BigDecimal("200"));
        upgradeCrew.setValues(upgradeCrewValues);
        actions.add(upgradeCrew);

        msg.setActions(actions);

        return msg;
    }

    // ========== Helper Methods ==========

    /**
     * Simulate the round and check if submarine survives.
     */
    private boolean simulateAndCheckSurvival(GameRoundServerMessage msg, List<Long> actionIds) {
        Values finalState = simulateFinalState(msg, actionIds);
        return !ClientUtils.isDead(finalState);
    }

    /**
     * Simulate the round and return final state.
     */
    private Values simulateFinalState(GameRoundServerMessage msg, List<Long> actionIds) {
        Map<Long, Action> actionMap = new HashMap<>();
        for (Action a : msg.getActions()) {
            actionMap.put(a.getId(), a);
        }

        Map<Integer, List<Effect>> effectsByStep = new HashMap<>();
        for (Effect e : msg.getEffects()) {
            effectsByStep.computeIfAbsent(e.getStep(), k -> new ArrayList<>()).add(e);
        }

        Values state = msg.getOurSubmarine().getValues();
        Set<Long> blockedEffectIds = new HashSet<>();

        int maxStep = Math.max(
                actionIds.size(),
                effectsByStep.keySet().stream().max(Integer::compareTo).orElse(0)
        );

        for (int step = 1; step <= maxStep; step++) {
            // Apply our action
            if (step <= actionIds.size()) {
                Long actionId = actionIds.get(step - 1);
                Action action = actionMap.get(actionId);
                if (action != null) {
                    state = ClientUtils.sumValues(state, action.getValues());
                    if (action.getEffectId() != -1) {
                        blockedEffectIds.add(action.getEffectId());
                    }
                }
            }

            // Apply effects
            List<Effect> stepEffects = effectsByStep.get(step);
            if (stepEffects != null) {
                for (Effect effect : stepEffects) {
                    if (!blockedEffectIds.contains(effect.getId())) {
                        state = ClientUtils.sumValues(state, effect.getValues());
                    }
                }
            }

            if (ClientUtils.isDead(state)) {
                break;
            }
        }

        return state;
    }
}
