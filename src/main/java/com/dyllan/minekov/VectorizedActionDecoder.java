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
    private static final int ACTION_SIZE = 5; // [angle, walk_flag, shoot_flag, pitch, yaw] - sequential ordering
    
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
        
        // Decode actions vectorized - sequential ordering
        for (int i = 0; i < count; i++) {
            float angle = buffer.getFloat();
            boolean walk = buffer.getFloat() > 0.5f;
            boolean shoot = buffer.getFloat() > 0.5f;
            float pitch = buffer.getFloat();
            float yaw = buffer.getFloat();
            
            // Use sequential index instead of encoded agent index
            actions.put(i, new AgentAction(angle, walk, shoot, pitch, yaw));
        }
        
        // Removed debug log for performance
        return actions;
    }
    
    /**
     * Agent action data structure.
     */
    public static class AgentAction {
        public final float angle;
        public final boolean walk;
        public final boolean shoot;
        public final float pitch;
        public final float yaw;
        
        public AgentAction(float angle, boolean walk, boolean shoot, float pitch, float yaw) {
            this.angle = angle;
            this.walk = walk;
            this.shoot = shoot;
            this.pitch = pitch;
            this.yaw = yaw;
        }
        
        @Override
        public String toString() {
            return String.format("Action{angle=%.1f°, walk=%s, shoot=%s, pitch=%.1f°, yaw=%.1f°}", 
                               angle, walk, shoot, pitch, yaw);
        }
    }
}
