package com.dyllan.minekov.training;

import java.util.ArrayList;
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

        // 🧠 Collect all RLOperator UUIDs
        List<String> rlIds = new ArrayList<>();
        for (TrainingGroup group : groups) {
            for (Team team : group.getTeams()) {
                for (AIOperator op : team.getOperators()) {
                    // if it's not an RLOperator, skip
                    if (!(op instanceof RLOperator)) {
                        continue;
                    }

                    if (op != null && op.isAlive()) {
                        rlIds.add(op.getUUID().toString());
                    }
                }
            }
        }

        if (!rlIds.isEmpty()) {
            Map<String, Object> data = Map.of("type", "tick", "operator_ids", rlIds);

            // TODO: convert operator_ids to another Map of operator UUIDs to details about them, including their xyz position, health, information about the previous step (ie. hit a target, took a hit, won round, lost round, etc.)
            // for now, we can just simply send the UUIDs with values of a Map containing their xyz and health

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
        // Check if all groups are complete
        return groups.stream().allMatch(TrainingGroup::isComplete);
    }

    public Player getProvisioningPlayer() {
        return provisioningPlayer;
    }
}
