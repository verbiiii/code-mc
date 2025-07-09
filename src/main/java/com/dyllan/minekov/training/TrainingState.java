package com.dyllan.minekov.training;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.dyllan.minekov.PythonBridge;
import com.dyllan.minekov.entities.AIOperator;
import com.dyllan.minekov.entities.RLOperator;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;


public class TrainingState {
    private List<TrainingGroup> groups;
    private Player provisioningPlayer;
    private final MinecraftServer server;

    // currentTick may be reset to 0 when the session restarts
    private int currentTick = 0;

    public TrainingState(Player provisioningPlayer, MinecraftServer server) {
        this.groups = new ArrayList<TrainingGroup>();
        this.provisioningPlayer = provisioningPlayer;
        this.server = server;
    }

    public void addGroup(TrainingGroup group) {
        this.groups.add(group);
    }

    public void tick() {
        boolean isFirstTick = currentTick == 0;
        currentTick++;

        for (TrainingGroup group : groups) {
            group.tick();
        }

        List<String> rlIds = new ArrayList<>();
        Map<String, Map<String, Object>> allOperatorData = new HashMap<>();

        for (TrainingGroup group : groups) {
            for (Team team : group.getTeams()) {
                String teamId = team.getTeamId();

                for (AIOperator op : team.getOperators()) {
                    Map<String, Object> info = new HashMap<>();
                    info.put("x", op.getX());
                    info.put("y", op.getY());
                    info.put("z", op.getZ());
                    info.put("health", op.getHealth());
                    info.put("team", teamId);
                    info.put("is_rl", op instanceof RLOperator);

                    if (op instanceof RLOperator rlOp) {
                        info.put("damage_taken_last_tick", rlOp.getDamageTakenLastTick());
                        info.put("damage_dealt_last_tick", rlOp.getDamageDealtLastTick());
                        info.put("deaths_last_tick", rlOp.getDeathsLastTick());
                        info.put("kills_last_tick", rlOp.getKillsLastTick());

                        rlIds.add(rlOp.getUUID().toString());

                        rlOp.clearTickDamageStats();
                    }

                    allOperatorData.put(op.getUUID().toString(), info);
                }
            }
        }

        boolean isDone = isComplete();

        if (!rlIds.isEmpty()) {
            Map<String, Object> data = Map.of(
                "type", "tick",
                "tick", currentTick,
                "is_first_tick", isFirstTick,
                "is_last_tick", isDone,
                "rl_operator_ids", rlIds,
                "all_operators", allOperatorData
            );

            PythonBridge.tickPython(data);
        }

        if (isDone) {
            for (TrainingGroup group : groups) {
                group.getTeams().forEach(team -> team.getOperators().forEach(op -> op.kill()));
            }

            MutableComponent message = Component.literal("§aTraining complete!");
            server.getPlayerList().broadcastSystemMessage(message, false);
        }
    }

    public boolean isComplete() {
        return groups.stream().allMatch(TrainingGroup::isComplete);
    }

    public Player getProvisioningPlayer() {
        return provisioningPlayer;
    }
}
