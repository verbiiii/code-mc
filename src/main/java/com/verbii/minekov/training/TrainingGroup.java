package com.verbii.minekov.training;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.verbii.minekov.entities.AIOperator;

import net.minecraft.world.entity.Entity;

public class TrainingGroup {
    private List<Team> teams;
    private int maxTicks;
    private int currentTick;
    private boolean allowRespawns;
    private final Random random = new Random();

    public TrainingGroup(int maxTicks, boolean allowRespawns) {
        this.teams = new ArrayList<Team>();
        this.maxTicks = maxTicks;
        this.currentTick = 0;
        this.allowRespawns = allowRespawns;
    }

    public void addTeam(Team team) {
        this.teams.add(team);
    }

    public void tick() {
        currentTick++;
    }

    public boolean isRoundComplete() {
        // e.g., only one team alive or time is up
        long aliveTeams = teams.stream().filter(Team::isAlive).count();

        if (!allowRespawns && aliveTeams <= 1) {
            return true;
        }

        if (maxTicks > 0 && currentTick >= maxTicks) {
            return true;
        }

        return false;
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

    /**
     * Chooses a random target from a different team than the requester.
     * Returns null if no valid target is found.
     */
    public AIOperator chooseRandomTarget(AIOperator requester) {
        if (!(requester instanceof AIOperator)) return null;

        // Find the team that the requester belongs to
        Team requesterTeam = null;
        for (Team team : teams) {
            if (team.getOperators().contains(requester)) {
                requesterTeam = team;
                break;
            }
        }

        if (requesterTeam == null) return null;

        // Collect all potential targets from other teams
        List<AIOperator> potentialTargets = new ArrayList<>();
        for (Team team : teams) {
            if (team != requesterTeam) {  // Different team
                for (AIOperator op : team.getOperators()) {
                    if (op != requester && op.isAlive()) {  // Not self and alive
                        potentialTargets.add(op);
                    }
                }
            }
        }

        // Return a random target or null if none available
        if (potentialTargets.isEmpty()) {
            return null;
        }
        
        return potentialTargets.get(random.nextInt(potentialTargets.size()));
    }
    
    public Team getWinningTeam() {
        return teams.stream()
                .filter(Team::isAlive)
                .findFirst()
                .orElse(null);
    }
}
