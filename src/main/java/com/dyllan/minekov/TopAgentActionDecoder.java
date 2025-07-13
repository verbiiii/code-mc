package com.dyllan.minekov;

import com.dyllan.minekov.entities.RLOperator;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Handles action decoding and execution for top agents in 1v1 mode.
 * Similar to BinaryActionDecoder but for single agent actions.
 */
public class TopAgentActionDecoder {
    
    private static final ConcurrentLinkedQueue<TopAgentAction> pendingActions = new ConcurrentLinkedQueue<>();
    
    public static void queueAction(int entityId, float angle, boolean walk, boolean shoot) {
        pendingActions.offer(new TopAgentAction(entityId, angle, walk, shoot));
    }
    
    public static void processPendingActions() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        
        TopAgentAction action;
        while ((action = pendingActions.poll()) != null) {
            // Find the entity by ID
            RLOperator agent = AgentIdManager.getById(action.entityId);
            if (agent != null && !agent.isRemoved()) {
                // Apply the action using available AIOperator methods
                agent.setYRot(action.angle);
                agent.setDeltaMovement(agent.getDeltaMovement().add(0, 0, action.walk ? 0.1 : 0));
                if (action.shoot) {
                    agent.shootForward();
                }
            }
        }
    }
    
    private static class TopAgentAction {
        final int entityId;
        final float angle;
        final boolean walk;
        final boolean shoot;
        
        TopAgentAction(int entityId, float angle, boolean walk, boolean shoot) {
            this.entityId = entityId;
            this.angle = angle;
            this.walk = walk;
            this.shoot = shoot;
        }
    }
}
