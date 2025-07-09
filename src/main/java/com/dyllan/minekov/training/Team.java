package com.dyllan.minekov.training;

import java.util.ArrayList;
import java.util.List;

import com.dyllan.minekov.entities.AIOperator;

public class Team {
    private List<AIOperator> operators;

    public Team() {
        this.operators = new ArrayList<>();
    }

    public void addOperator(AIOperator op) {
        this.operators.add(op);
    }

    public void tick() {
        for (AIOperator op : operators) {
            op.tick();  // RL/dumb action
        }
    }

    public boolean isAlive() {
        return operators.stream().anyMatch(AIOperator::isAlive);
    }

    public List<AIOperator> getOperators() {
        return operators;
    }
}
