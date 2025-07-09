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

    public TrainingState(Player provisioningPlayer, MinecraftServer server) {
        this.groups = new ArrayList<TrainingGroup>();
        this.provisioningPlayer = provisioningPlayer;
        this.server = server;
    }

    public void addGroup(TrainingGroup group) {
        this.groups.add(group);
    }

    public void tick() {
        for (TrainingGroup group : groups) {
            group.tick();
        }

        // 🧐 Collect all RLOperator UUIDs
        List<String> rlIds = new ArrayList<>();
        Map<String, Map<String, Object>> allOperatorData = new HashMap<>();

        for (TrainingGroup group : groups) {
            for (Team team : group.getTeams()) {
                String teamId = team.getTeamId(); // assume you have getId()

                for (AIOperator op : team.getOperators()) {
                    Map<String, Object> info = new HashMap<>();
                    info.put("x", op.getX());
                    info.put("y", op.getY());
                    info.put("z", op.getZ());
                    info.put("health", op.getHealth());
                    info.put("team", teamId);
                    info.put("is_rl", op instanceof RLOperator);
                    allOperatorData.put(op.getUUID().toString(), info);

                    if (op instanceof RLOperator && op.isAlive()) {
                        rlIds.add(op.getUUID().toString());
                    }
                }
            }
        }

        if (!rlIds.isEmpty()) {
            Map<String, Object> data = Map.of(
                "type", "tick",
                "rl_operator_ids", rlIds,
                "all_operators", allOperatorData
            );

            PythonBridge.tickPython(data);
        }

        if (isComplete()) {
            // kill everything
            for (TrainingGroup group : groups) {
                group.getTeams().forEach(team -> team.getOperators().forEach(op -> op.kill()));
            }

            // notify the player
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
