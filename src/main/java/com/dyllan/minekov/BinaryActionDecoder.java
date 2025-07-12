package com.dyllan.minekov;

import com.dyllan.minekov.entities.RLOperator;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Ultra-compact binary protocol decoder for WebSocket messages.
 * Processes 12-byte actions with 32-bit agent IDs.
 */
public class BinaryActionDecoder {
    private static final short MAGIC = (short) 0xACE5;
    
    /**
     * Process binary WebSocket message containing agent actions
     */
    public void processBinaryMessage(byte[] data) {
        if (data.length < 4) {
            System.err.println("⚠️ Binary message too short: " + data.length + " bytes");
            return;
        }
        
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        
        // Parse header
        short magic = buf.getShort();
        if (magic != MAGIC) {
            System.err.println("⚠️ Invalid magic number: 0x" + Integer.toHexString(magic & 0xFFFF));
            return;
        }
        
        short actionCount = buf.getShort();
        
        if (buf.remaining() < actionCount * 12) {
            System.err.println("⚠️ Incomplete action data. Expected: " + (actionCount * 12) + ", Got: " + buf.remaining());
            return;
        }
        
        int processed = 0;
        
        // Process actions - BLAZING FAST
        for (int i = 0; i < actionCount; i++) {
            if (buf.remaining() < 12) break;
            
            int agentId = buf.getInt();        // 4 bytes
            short angleRaw = buf.getShort();   // 2 bytes  
            short flags = buf.getShort();      // 2 bytes
            buf.getInt();                      // 4 bytes reserved (skip)
            
            // Direct O(1) lookup - no hashing!
            RLOperator op = AgentIdManager.getById(agentId);
            if (op != null) {
                try {
                    if ((flags & 2) != 0) { // Move flag
                        float angle = (angleRaw & 0xFFFF) * 360.0f / 65535.0f;
                        op.moveTowards(angle, 0.13f);
                    }
                    if ((flags & 1) != 0) op.shootForward();      // Fire flag
                    // Note: Sprint/crouch flags reserved for future use
                    
                    processed++;
                } catch (Exception e) {
                    System.err.println("⚠️ Error processing action for agent " + agentId + ": " + e.getMessage());
                }
            }
            // Note: Missing agents are normal (they may have died)
        }
        
        System.out.println("⚡ Processed " + processed + "/" + actionCount + " actions (" + 
                          AgentIdManager.getActiveCount() + " active agents)");
    }
}
