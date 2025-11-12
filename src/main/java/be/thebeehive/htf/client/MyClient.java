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

    private static final BigDecimal LOW_HULL       = new BigDecimal("60000");
    private static final BigDecimal CRITICAL_HULL  = new BigDecimal("30000");
    private static final BigDecimal LOW_CREW       = new BigDecimal("700");
    private static final BigDecimal CRITICAL_CREW  = new BigDecimal("300");

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

        if (ClientUtils.isDead(current)) {
            System.out.printf("Round %d | Submarine already destroyed. Sending no actions.%n", msg.getRound());
            client.send(new SelectActionsClientMessage(msg.getRoundId(), Collections.emptyList()));
            return;
        }

        // Use DecisionEngine for planning
        DecisionEngine engine = new DecisionEngine();
        List<Long> chosenActions = engine.planActions(msg);
        client.send(new SelectActionsClientMessage(msg.getRoundId(), chosenActions));

        // Log trace
        String trace = engine.getTraceLog();
        System.out.print(trace);
    }

    /**
     * High-level planning for a single round:
     * 1. Cancel harmful effects when possible.
     * 2. Use remaining steps for beneficial actions (repairs/heals/upgrades).
     */
    private List<Long> planRoundActions(GameRoundServerMessage.Values startState,
                                        List<GameRoundServerMessage.Action> actions,
                                        List<GameRoundServerMessage.Effect> effects) {

        List<Long> chosen = new ArrayList<>();
        Set<Long> usedActionIds = new HashSet<>();

        GameRoundServerMessage.Values simulated = startState;
        int step = 1;

        // 1. Cancel harmful effects (hull or crew damage)
        List<GameRoundServerMessage.Effect> harmfulEffects = findHarmfulEffects(effects);
        for (GameRoundServerMessage.Effect effect : harmfulEffects) {
            if (step > MAX_ACTIONS_PER_ROUND) break;
            if (step > effect.getStep()) continue;

            GameRoundServerMessage.Action counter = findCounterAction(effect, actions, usedActionIds);
            if (counter == null || counter.getValues() == null) continue;

            GameRoundServerMessage.Values after =
                    ClientUtils.sumValues(simulated, counter.getValues());

            if (ClientUtils.isDead(after)) {
                // Do not take a counteraction that kills us by itself
                continue;
            }

            chosen.add(counter.getId());
            usedActionIds.add(counter.getId());
            simulated = after;
            step++;
        }

        // 2. Use remaining steps for the best beneficial actions
        while (step <= MAX_ACTIONS_PER_ROUND) {
            GameRoundServerMessage.Action best =
                    chooseBestBeneficialAction(actions, usedActionIds, simulated);

            if (best == null || best.getValues() == null) {
                break;
            }

            GameRoundServerMessage.Values after =
                    ClientUtils.sumValues(simulated, best.getValues());

            if (ClientUtils.isDead(after)) {
                // Never choose an action that directly kills us
                break;
            }

            chosen.add(best.getId());
            usedActionIds.add(best.getId());
            simulated = after;
            step++;
        }

        return chosen;
    }

    private List<GameRoundServerMessage.Effect> findHarmfulEffects(List<GameRoundServerMessage.Effect> effects) {
        List<GameRoundServerMessage.Effect> harmful = new ArrayList<>();
        for (GameRoundServerMessage.Effect e : effects) {
            if (isHarmfulEffect(e)) {
                harmful.add(e);
            }
        }
        // earliest step first, then largest total damage
        harmful.sort((a, b) -> {
            int byStep = Integer.compare(a.getStep(), b.getStep());
            if (byStep != 0) return byStep;

            BigDecimal da = totalNegativeImpact(a.getValues());
            BigDecimal db = totalNegativeImpact(b.getValues());
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

    private BigDecimal totalNegativeImpact(GameRoundServerMessage.Values v) {
        if (v == null) return BigDecimal.ZERO;
        BigDecimal h = safe(v.getHullStrength());
        BigDecimal c = safe(v.getCrewHealth());
        BigDecimal sum = BigDecimal.ZERO;
        if (h.compareTo(BigDecimal.ZERO) < 0) sum = sum.add(h);
        if (c.compareTo(BigDecimal.ZERO) < 0) sum = sum.add(c);
        return sum;
    }

    private GameRoundServerMessage.Action findCounterAction(GameRoundServerMessage.Effect effect,
                                                            List<GameRoundServerMessage.Action> actions,
                                                            Set<Long> usedActionIds) {
        for (GameRoundServerMessage.Action action : actions) {
            if (action.getEffectId() == effect.getId() && !usedActionIds.contains(action.getId())) {
                return action;
            }
        }
        return null;
    }

    /**
     * Choose the best beneficial action given the current simulated state.
     */
    private GameRoundServerMessage.Action chooseBestBeneficialAction(
            List<GameRoundServerMessage.Action> actions,
            Set<Long> usedActionIds,
            GameRoundServerMessage.Values state
    ) {
        GameRoundServerMessage.Action best = null;
        BigDecimal bestScore = BigDecimal.ZERO; // require strictly positive score

        for (GameRoundServerMessage.Action action : actions) {
            if (action == null || action.getValues() == null) continue;
            if (usedActionIds.contains(action.getId())) continue;

            BigDecimal score = scoreAction(action.getValues(), state);
            if (score.compareTo(bestScore) > 0) {
                bestScore = score;
                best = action;
            }
        }
        return best;
    }

    /**
     * Scoring function: decide how good an action is,
     * depending on current hull / crew situation.
     */
    private BigDecimal scoreAction(GameRoundServerMessage.Values effect,
                                   GameRoundServerMessage.Values state) {

        BigDecimal deltaHull     = safe(effect.getHullStrength());
        BigDecimal deltaCrew     = safe(effect.getCrewHealth());
        BigDecimal deltaMaxHull  = safe(effect.getMaxHullStrength());
        BigDecimal deltaMaxCrew  = safe(effect.getMaxCrewHealth());

        BigDecimal currentHull   = safe(state.getHullStrength());
        BigDecimal currentCrew   = safe(state.getCrewHealth());

        // Hard rule: if crew is critical, never accept crew damage
        if (currentCrew.compareTo(CRITICAL_CREW) <= 0
                && deltaCrew.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.valueOf(-1_000_000);
        }

        // Weights for hull and crew
        BigDecimal hullWeight  = BigDecimal.ONE;
        BigDecimal crewWeight  = new BigDecimal("2.0"); // crew generally more important
        BigDecimal maxHullWeight = new BigDecimal("0.3");
        BigDecimal maxCrewWeight = new BigDecimal("0.3");

        // If hull is low, care more about hull repair
        if (currentHull.compareTo(LOW_HULL) < 0) {
            hullWeight = hullWeight.add(new BigDecimal("1.0"));
        }
        if (currentHull.compareTo(CRITICAL_HULL) < 0) {
            hullWeight = hullWeight.add(new BigDecimal("1.0"));
        }

        // If crew is low, heavily prioritize crew healing
        if (currentCrew.compareTo(LOW_CREW) < 0) {
            crewWeight = crewWeight.add(new BigDecimal("2.0"));
        }
        if (currentCrew.compareTo(CRITICAL_CREW) < 0) {
            crewWeight = crewWeight.add(new BigDecimal("3.0"));
        }

        // Penalise crew loss more strongly when low
        if (deltaCrew.compareTo(BigDecimal.ZERO) < 0 && currentCrew.compareTo(LOW_CREW) < 0) {
            crewWeight = crewWeight.add(new BigDecimal("2.0"));
        }

        BigDecimal score = BigDecimal.ZERO;
        score = score.add(deltaHull.multiply(hullWeight));
        score = score.add(deltaCrew.multiply(crewWeight));
        score = score.add(deltaMaxHull.multiply(maxHullWeight));
        score = score.add(deltaMaxCrew.multiply(maxCrewWeight));

        return score;
    }

    private BigDecimal safe(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
