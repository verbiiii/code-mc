package com.dyllan.minekov.training;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.dyllan.minekov.entities.AIOperator;

public class Team {
    private static final AtomicInteger nextId = new AtomicInteger(1);

    private final String teamId;
    private final List<AIOperator> operators;

    public Team() {
        this.teamId = "team-" + nextId.getAndIncrement();
        this.operators = new ArrayList<>();
    }

    public void addOperator(AIOperator op) {
        this.operators.add(op);
    }

    // redundant tick call
    // public void tick() {
    //     for (AIOperator op : operators) {
    //         op.tick();  // RL/dumb action
    //     }
    // }

    public boolean isAlive() {
        return operators.stream().anyMatch(AIOperator::isAlive);
    }

    public List<AIOperator> getOperators() {
        return operators;
    }

    public String getTeamId() {
        return teamId;
    }
}
