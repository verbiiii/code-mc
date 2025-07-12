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
    private ByteBuffer incompleteBuffer = null; // Buffer for incomplete messages
    
    /**
     * Process binary WebSocket message containing agent actions
     */
    public void processBinaryMessage(byte[] data) {
        ByteBuffer buf;
        
        // If we have incomplete data from previous message, combine it
        if (incompleteBuffer != null) {
            // Create new buffer with combined data
            byte[] combined = new byte[incompleteBuffer.remaining() + data.length];
            incompleteBuffer.get(combined, 0, incompleteBuffer.remaining());
            System.arraycopy(data, 0, combined, incompleteBuffer.remaining(), data.length);
            buf = ByteBuffer.wrap(combined).order(ByteOrder.BIG_ENDIAN);
            incompleteBuffer = null;
        } else {
            buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        }
        
        if (buf.remaining() < 4) {
            // Not even enough for header, save for next message
            incompleteBuffer = buf;
            return;
        }
        
        // Parse header
        int headerStart = buf.position();
        short magic = buf.getShort();
        if (magic != MAGIC) {
            System.err.println("⚠️ Invalid magic number: 0x" + Integer.toHexString(magic & 0xFFFF));
            return;
        }
        
        short actionCount = buf.getShort();
        int requiredBytes = actionCount * 12;
        
        if (buf.remaining() < requiredBytes) {
            // Incomplete message, save the entire buffer for next time
            buf.position(headerStart); // Reset to start of header
            incompleteBuffer = ByteBuffer.allocate(buf.remaining());
            incompleteBuffer.put(buf);
            incompleteBuffer.flip();
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
