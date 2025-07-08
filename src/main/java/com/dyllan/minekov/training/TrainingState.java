package com.dyllan.minekov.training;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.entity.player.Player;


public class TrainingState {
    private List<TrainingGroup> groups;
    private Player provisioningPlayer;

    public TrainingState(Player provisioningPlayer) {
        this.groups = new ArrayList<TrainingGroup>();
        this.provisioningPlayer = provisioningPlayer;
    }

    public void addGroup(TrainingGroup group) {
        this.groups.add(group);
    }

    public void tick() {
        // no need to tick if we're completed.
        if (isComplete()) {
            return;
        }

        for (TrainingGroup group : groups) {
            group.tick();
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
