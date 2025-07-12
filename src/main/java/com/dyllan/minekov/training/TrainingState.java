package com.dyllan.minekov.training;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.dyllan.minekov.ModEntities;
import com.dyllan.minekov.PythonBridge;
import com.dyllan.minekov.entities.AIOperator;
import com.dyllan.minekov.entities.DumbOperator;
import com.dyllan.minekov.entities.RLOperator;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

public class TrainingState {
    private static final int NUM_GROUPS = 4; // ← change this to 1, 100, etc. for # of 1v1s
    private final boolean selfPlay = true; // ← set to false to use DumbOperator

    private List<TrainingGroup> groups = new ArrayList<>();
    private Player provisioningPlayer;
    private final MinecraftServer server;

    private final int numRounds;
    private int currentRound = 0;
    private int currentRoundTick = 0;
    private boolean roundActive = false;

    public TrainingState(Player provisioningPlayer, MinecraftServer server, int rounds) {
        this.numRounds = rounds;
        this.provisioningPlayer = provisioningPlayer;
        this.server = server;

        sendTickEvent("start_session", Map.of("rounds", rounds));
        setupRound(); // begin first round
    }

    public void tick() {
        if (!roundActive) return;

        boolean isFirstTick = currentRoundTick == 0;
        currentRoundTick++;

        for (TrainingGroup group : groups) {
            group.tick();
        }

        List<String> rlIds = new ArrayList<>();
        Map<String, Map<String, Object>> allOperatorData = new HashMap<>();

        Map<String, Team> operatorTeamMap = new HashMap<>();
        Map<String, AIOperator> allOperators = new HashMap<>();

        for (TrainingGroup group : groups) {
            for (Team team : group.getTeams()) {
                for (AIOperator op : team.getOperators()) {
                    String uuid = op.getUUID().toString();
                    operatorTeamMap.put(uuid, team);
                    allOperators.put(uuid, op);
                }
            }
        }

        for (Map.Entry<String, AIOperator> entry : allOperators.entrySet()) {
            String uuid = entry.getKey();
            AIOperator op = entry.getValue();
            Team myTeam = operatorTeamMap.get(uuid);

            Map<String, Object> info = new HashMap<>();
            info.put("x", op.getX());
            info.put("y", op.getY());
            info.put("z", op.getZ());
            info.put("health", op.getHealth());
            info.put("team", myTeam.getTeamId());
            info.put("is_rl", op instanceof RLOperator);

            if (op instanceof RLOperator rlOp) {
                info.put("damage_taken_last_tick", rlOp.getDamageTakenLastTick());
                info.put("damage_dealt_last_tick", rlOp.getDamageDealtLastTick());
                info.put("deaths_last_tick", rlOp.getDeathsLastTick());
                info.put("kills_last_tick", rlOp.getKillsLastTick());

                AIOperator opponent = allOperators.values().stream()
                    .filter(other -> !other.getUUID().equals(op.getUUID()))
                    .filter(other -> !operatorTeamMap.get(other.getUUID().toString()).equals(myTeam))
                    .findFirst()
                    .orElse(null);

                if (opponent != null) {
                    Map<String, Object> opp = new HashMap<>();
                    opp.put("x", opponent.getX());
                    opp.put("y", opponent.getY());
                    opp.put("z", opponent.getZ());
                    info.put("opponent", opp);
                }

                rlIds.add(uuid);
                rlOp.clearTickDamageStats();
            }

            allOperatorData.put(uuid, info);
        }

        boolean roundDone = isRoundComplete();

        Map<String, Object> tickPayload = new HashMap<>();
        tickPayload.put("type", "tick");
        tickPayload.put("tick", currentRoundTick);
        tickPayload.put("round", currentRound);
        tickPayload.put("is_first_tick", isFirstTick);
        tickPayload.put("is_last_tick", roundDone);
        tickPayload.put("rl_operator_ids", rlIds);
        tickPayload.put("all_operators", allOperatorData);

        PythonBridge.tickPython(tickPayload);

        if (roundDone) {
            cleanupRound();
            currentRound++;

            if (currentRound >= numRounds) {
                endSession();
            } else {
                setupRound();
            }
        }
    }

    public boolean isComplete() {
        return !roundActive && currentRound >= numRounds;
    }

    private boolean isRoundComplete() {
        return groups.stream().allMatch(TrainingGroup::isComplete);
    }

    private void setupRound() {
        groups.clear();
        currentRoundTick = 0;
        roundActive = true;

        ServerLevel world = server.overworld();
        double team1X = 19.5, team1Z = 17.5;
        double team2X = 19.5, team2Z = 9.5;
        double baseY = 2.0;
        double offsetY = 0.3;

        for (int i = 0; i < NUM_GROUPS; i++) {
            double y = baseY + (i * offsetY);

            TrainingGroup group = new TrainingGroup(100); // 600 ticks is 30 seconds

            RLOperator rl1 = ModEntities.RL_OPERATOR.get().create(world);
            rl1.moveTo(team1X, y, team1Z, 180.0f, 0.0f);
            world.addFreshEntity(rl1);
            Team team1 = new Team();
            team1.addOperator(rl1);

            AIOperator opponent;
            if (selfPlay) {
                RLOperator rl2 = ModEntities.RL_OPERATOR.get().create(world);
                rl2.moveTo(team2X, y, team2Z, 0.0f, 0.0f);
                world.addFreshEntity(rl2);
                opponent = rl2;
            } else {
                DumbOperator dumb = ModEntities.DUMB_OPERATOR.get().create(world);
                dumb.moveTo(team2X, y, team2Z, 0.0f, 0.0f);
                world.addFreshEntity(dumb);
                opponent = dumb;
            }
            Team team2 = new Team();
            team2.addOperator(opponent);

            group.addTeam(team1);
            group.addTeam(team2);
            groups.add(group);
        }

        sendTickEvent("start_round", Map.of("round", currentRound));
        broadcastToPlayers("§eRound " + (currentRound + 1) + " started!");
    }

    private void cleanupRound() {
        roundActive = false;
        for (TrainingGroup group : groups) {
            for (Team team : group.getTeams()) {
                for (AIOperator op : team.getOperators()) {
                    op.kill();
                }
            }
        }

        sendTickEvent("end_round", Map.of("round", currentRound));
        broadcastToPlayers("§cRound " + (currentRound + 1) + " complete.");
    }

    private void endSession() {
        sendTickEvent("end_session", Map.of("rounds", numRounds));
        broadcastToPlayers("§aTraining session complete!");
    }

    private void sendTickEvent(String event, Map<String, Object> extra) {
        Map<String, Object> payload = new HashMap<>(extra);
        payload.put("type", "tick");
        payload.put("event", event);
        payload.put("round", currentRound);
        PythonBridge.tickPython(payload);
    }

    private void broadcastToPlayers(String message) {
        server.getPlayerList().broadcastSystemMessage(Component.literal(message), false);
    }

    public Player getProvisioningPlayer() {
        return provisioningPlayer;
    }

    public void stop() {
        if (roundActive) {
            cleanupRound();
            roundActive = false;
        }
        currentRound = 0;
        currentRoundTick = 0;
        groups.clear();
        sendTickEvent("stop_session", Map.of("rounds", numRounds));
        broadcastToPlayers("§cTraining session forcefully stopped.");
    }

    public List<TrainingGroup> getGroups() {
        return groups;
    }
}
