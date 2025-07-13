package com.dyllan.minekov;

import com.dyllan.minekov.entities.RLOperator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Collection;

/**
 * Ultra-efficient agent ID management system.
 * Uses 32-bit integers instead of UUIDs for massive performance gains.
 */
public class AgentIdManager {
    private static final AtomicInteger nextId = new AtomicInteger(1);
    private static final ConcurrentHashMap<Integer, RLOperator> operatorsById = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<RLOperator, Integer> idsByOperator = new ConcurrentHashMap<>();
    
    /**
     * Assign a unique ID to an agent
     */
    public static int assignId(RLOperator op) {
        int id = nextId.getAndIncrement();
        operatorsById.put(id, op);
        idsByOperator.put(op, id);
        // Removed debug log for performance
        return id;
    }
    
    /**
     * Release an agent ID when entity is removed
     */
    public static void releaseId(RLOperator op) {
        Integer id = idsByOperator.remove(op);
        if (id != null) {
            operatorsById.remove(id);
            System.out.println("💀 Agent " + id + " released");
        }
    }
    
    /**
     * Get agent by ID - O(1) lookup
     */
    public static RLOperator getById(int id) {
        return operatorsById.get(id);
    }
    
    /**
     * Get ID for agent - O(1) lookup
     */
    public static int getId(RLOperator op) {
        return idsByOperator.getOrDefault(op, 0);
    }
    
    /**
     * Get all active agents
     */
    public static Collection<RLOperator> getAllOperators() {
        return operatorsById.values();
    }
    
    /**
     * Get current agent count
     */
    public static int getActiveCount() {
        return operatorsById.size();
    }
    
    /**
     * Get next ID that will be assigned (for debugging)
     */
    public static int getNextId() {
        return nextId.get();
    }
}
