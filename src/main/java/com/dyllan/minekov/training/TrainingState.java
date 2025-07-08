package com.dyllan.minekov.training;

import java.util.ArrayList;
import java.util.List;

public class TrainingState {
    private List<TrainingGroup> groups;

    public TrainingState() {
        this.groups = new ArrayList<TrainingGroup>();
    }

    public void addGroup(TrainingGroup group) {
        this.groups.add(group);
    }

    public void tick() {
        for (TrainingGroup group : groups) {
            group.tick();
        }
    }
}
