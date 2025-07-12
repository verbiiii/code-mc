package com.dyllan.minekov.training;

import java.util.ArrayList;
import java.util.List;

import com.dyllan.minekov.entities.AIOperator;

import net.minecraft.world.entity.Entity;

public class TrainingGroup {
    private List<Team> teams;
    private int maxTicks;
    private int currentTick;

    public TrainingGroup(int maxTicks) {
        this.teams = new ArrayList<Team>();
        this.maxTicks = maxTicks;
        this.currentTick = 0;
    }

    public void addTeam(Team team) {
        this.teams.add(team);
    }

    public void tick() {
        currentTick++;
        // for (Team team : teams) {
        //     team.tick();  // delegate control logic
        // }
    }

    public boolean isComplete() {
        // e.g., only one team alive or time is up
        long aliveTeams = teams.stream().filter(Team::isAlive).count();
        return aliveTeams <= 1 || currentTick >= maxTicks;
    }

    public List<Team> getTeams() {
        return teams;
    }

    public boolean contains(Entity e) {
        if (!(e instanceof AIOperator op)) return false;
        for (Team team : teams) {
            if (team.getOperators().contains(op)) {
                return true;
            }
        }
        return false;
    }

    public boolean shouldInteract(Entity a, Entity b) {
        return this.contains(a) && this.contains(b) && a != b;
    }
}
