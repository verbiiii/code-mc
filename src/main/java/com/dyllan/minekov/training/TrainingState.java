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

        // First: build maps of operator UUID -> team and operator UUID -> entity
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

        // Now: build full info map
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

                // Add opponent info (first from non-own-team)
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

        TrainingGroup group = new TrainingGroup(200); // 10 seconds @ 20tps

        ServerLevel world = server.overworld();
        double rlX = 19.5, rlY = 2, rlZ = 17.5;
        double dumbX = 19.5, dumbY = 2, dumbZ = 9.5;

        RLOperator rl = ModEntities.RL_OPERATOR.get().create(world);
        rl.moveTo(rlX, rlY, rlZ, 180.0f, 0.0f);
        world.addFreshEntity(rl);

        DumbOperator dumb = ModEntities.DUMB_OPERATOR.get().create(world);
        dumb.moveTo(dumbX, dumbY, dumbZ, 0.0f, 0.0f);
        world.addFreshEntity(dumb);

        Team team1 = new Team();
        team1.addOperator(rl);
        Team team2 = new Team();
        team2.addOperator(dumb);

        group.addTeam(team1);
        group.addTeam(team2);
        groups.add(group);

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
        // forcibly stop the training session
        if (roundActive) {
            cleanupRound();
            roundActive = false;
        }
        currentRound = 0;
        currentRoundTick = 0;
        groups.clear();
        sendTickEvent("stop_session", Map.of("rounds", numRounds));
        broadcastToPlayers("§cTraining session forcefully stopped.");
        // PythonBridge.stopPython(); // TODO: maybe python wants to know about it?
    }
}