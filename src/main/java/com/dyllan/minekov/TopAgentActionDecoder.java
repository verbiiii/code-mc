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
        int actionCount = 0;
        while ((action = pendingActions.poll()) != null) {
            actionCount++;
            // Find the entity by ID
            RLOperator agent = AgentIdManager.getById(action.entityId);
            if (agent != null && !agent.isRemoved()) {
                System.out.println("🤖 Applying action to agent " + action.entityId + " - Angle: " + action.angle + ", Walk: " + action.walk + ", Shoot: " + action.shoot);
                
                // Apply rotation
                agent.setYRot(action.angle);
                agent.setYHeadRot(action.angle);
                
                // Apply movement - use the entity's movement system
                if (action.walk) {
                    // Calculate forward direction based on rotation
                    double radians = Math.toRadians(action.angle);
                    double forwardX = -Math.sin(radians) * 0.2; // Move forward at walking speed
                    double forwardZ = Math.cos(radians) * 0.2;
                    
                    // Set movement
                    agent.setDeltaMovement(forwardX, agent.getDeltaMovement().y, forwardZ);
                    System.out.println("🚶 Moving agent - X: " + forwardX + ", Z: " + forwardZ);
                }
                
                // Apply shooting
                if (action.shoot) {
                    System.out.println("🔫 Agent shooting!");
                    agent.shootForward();
                }
            } else {
                System.out.println("⚠️ Agent " + action.entityId + " not found or removed");
            }
        }
        if (actionCount > 0) {
            System.out.println("📝 Processed " + actionCount + " top agent actions");
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
