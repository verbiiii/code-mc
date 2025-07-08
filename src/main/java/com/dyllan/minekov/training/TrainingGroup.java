package com.dyllan.minekov.training;

import java.util.ArrayList;
import java.util.List;

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
        for (Team team : teams) {
            team.tick();  // delegate control logic
        }

        // Check for end conditions
        if (isComplete()) {
            System.out.println("Training session complete after " + currentTick + " ticks.");
        }
    }

    private boolean isComplete() {
        // e.g., only one team alive or time is up
        long aliveTeams = teams.stream().filter(Team::isAlive).count();
        return aliveTeams <= 1 || currentTick >= maxTicks;
    }
}
