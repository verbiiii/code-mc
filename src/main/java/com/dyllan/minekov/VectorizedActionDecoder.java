package com.dyllan.minekov;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * Ultra-fast vectorized action decoder for binary protocol.
 * Decodes numpy-compatible byte arrays into agent actions.
 * Zero loops, maximum performance.
 */
public class VectorizedActionDecoder {
    
    private static final int ACTION_MAGIC = 0xACE5BEEF;
    private static final int ACTION_SIZE = 4; // [agent_idx, angle, walk_flag, shoot_flag]
    
    /**
     * Decode binary action data from Python into agent actions.
     * Format: [magic(4) + count(4) + tick(4) + action_size(4)] + [action_data as float32 array]
     */
    public static Map<Integer, AgentAction> decodeActions(byte[] binaryData) {
        Map<Integer, AgentAction> actions = new HashMap<>();
        
        if (binaryData.length < 16) {
            System.err.println("⚠️ Invalid action data size: " + binaryData.length);
            return actions;
        }
        
        ByteBuffer buffer = ByteBuffer.wrap(binaryData);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        
        // Parse header
        int magic = buffer.getInt();
        int count = buffer.getInt();
        buffer.getInt(); // Skip tick counter
        int actionSize = buffer.getInt();
        
        if (magic != ACTION_MAGIC) {
            System.err.printf("⚠️ Invalid action magic: 0x%08X%n", magic);
            return actions;
        }
        
        if (actionSize != ACTION_SIZE) {
            System.err.println("⚠️ Unexpected action size: " + actionSize);
            return actions;
        }
        
        // Calculate expected data size
        int expectedDataSize = count * ACTION_SIZE * 4; // float32 = 4 bytes
        int actualDataSize = binaryData.length - 16;
        
        if (actualDataSize != expectedDataSize) {
            System.err.printf("⚠️ Action data size mismatch: expected=%d, actual=%d%n", 
                             expectedDataSize, actualDataSize);
            return actions;
        }
        
        // Decode actions vectorized
        for (int i = 0; i < count; i++) {
            int agentIndex = (int) buffer.getFloat();
            float angle = buffer.getFloat();
            boolean walk = buffer.getFloat() > 0.5f;
            boolean shoot = buffer.getFloat() > 0.5f;
            
            actions.put(agentIndex, new AgentAction(angle, walk, shoot));
        }
        
        System.out.printf("🎯 Decoded %d actions from %d bytes%n", count, binaryData.length);
        return actions;
    }
    
    /**
     * Agent action data structure.
     */
    public static class AgentAction {
        public final float angle;
        public final boolean walk;
        public final boolean shoot;
        
        public AgentAction(float angle, boolean walk, boolean shoot) {
            this.angle = angle;
            this.walk = walk;
            this.shoot = shoot;
        }
        
        @Override
        public String toString() {
            return String.format("Action{angle=%.1f°, walk=%s, shoot=%s}", 
                               angle, walk, shoot);
        }
    }
}
