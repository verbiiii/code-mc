package com.dyllan.minekov;

import com.dyllan.minekov.entities.RLOperator;
import net.minecraft.server.level.ServerPlayer;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Dedicated WebSocket client for single top agent communication.
 * Connects to /top-agent endpoint for 1v1 combat.
 */
public class TopAgentWebSocketClient {
    
    private final WebSocketClient webSocket;
    private RLOperator topAgent;
    private ServerPlayer playerOpponent;
    
    public TopAgentWebSocketClient(URI uri) {
        this.webSocket = new WebSocketClient(uri);
        this.webSocket.setBinaryMessageHandler(this::handleTopAgentActions);
    }
    
    public void connect() {
        webSocket.connect();
    }
    
    public void disconnect() {
        webSocket.shutdown();
    }
    
    public boolean isConnected() {
        return webSocket.isConnected();
    }
    
    public void setTopAgent(RLOperator agent, ServerPlayer player) {
        this.topAgent = agent;
        this.playerOpponent = player;
    }
    
    /**
     * Send single agent observation to top-agent endpoint
     */
    public void sendTopAgentObservation() {
        if (topAgent == null || playerOpponent == null || !webSocket.isConnected()) {
            System.out.println("⚠️ Cannot send observation - missing agent, player, or connection");
            return;
        }
        
        // Create single agent observation with player as opponent
        byte[] observationData = encodeSingleAgentObservation(topAgent, playerOpponent);
        System.out.println("📡 Sending " + observationData.length + " bytes to top-agent endpoint");
        webSocket.sendBinary(observationData);
    }
    
    private byte[] encodeSingleAgentObservation(RLOperator agent, ServerPlayer player) {
        // Format: magic(4) + obs_size(4) + observation_data(6 floats for positions)
        ByteBuffer buffer = ByteBuffer.allocate(8 + 6 * 4); // 32 bytes total
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        
        // Header
        buffer.putInt(0xDEADBEEF); // Magic for single agent
        buffer.putInt(6); // Observation size (just positions)
        
        // Agent position
        buffer.putFloat((float) agent.getX());
        buffer.putFloat((float) agent.getY());
        buffer.putFloat((float) agent.getZ());
        
        // Player (opponent) position
        buffer.putFloat((float) player.getX());
        buffer.putFloat((float) player.getY());
        buffer.putFloat((float) player.getZ());
        
        return buffer.array();
    }
    
    private void handleTopAgentActions(byte[] data) {
        if (topAgent == null || data.length < 8) {
            System.out.println("⚠️ Cannot handle action - missing agent or insufficient data (" + data.length + " bytes)");
            return;
        }
        
        try {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            
            int magic = buffer.getInt();
            int actionCount = buffer.getInt();
            
            System.out.println("🎮 Received action - Magic: 0x" + Integer.toHexString(magic) + ", Count: " + actionCount);
            
            if (magic != 0xBEEFDEAD || actionCount != 1) {
                System.out.println("⚠️ Invalid action format");
                return;
            }
            
            // Read single agent actions
            int xAction = buffer.getInt();
            buffer.getInt(); // yAction - not used currently but part of protocol
            int walkAction = buffer.getInt();
            int shootAction = buffer.getInt();
            
            // Apply actions to the top agent
            float angle = (xAction / 8.0f) * 360.0f;
            boolean walk = walkAction > 0;
            boolean shoot = shootAction > 0;
            
            System.out.println("🎯 Applying actions - Angle: " + angle + ", Walk: " + walk + ", Shoot: " + shoot);
            
            // Queue the action for main thread execution
            TopAgentActionDecoder.queueAction(topAgent.getId(), angle, walk, shoot);
            
        } catch (Exception e) {
            System.err.println("Error processing top agent action: " + e.getMessage());
        }
    }
}
