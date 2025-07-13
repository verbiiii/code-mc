package com.dyllan.minekov;

import com.dyllan.minekov.entities.RLOperator;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Ultra-compact binary protocol decoder for WebSocket messages.
 * Processes 12-byte actions with 32-bit agent IDs.
 * Thread-safe: schedules actions on main server thread to prevent ConcurrentModificationException.
 */
public class BinaryActionDecoder {
    private static final short MAGIC = (short) 0xACE5;
    private ByteBuffer incompleteBuffer = null; // Buffer for incomplete messages
    
    // Thread-safe queue for actions to be processed on main thread
    private static final ConcurrentLinkedQueue<Runnable> pendingActions = new ConcurrentLinkedQueue<>();
    
    /**
     * Process pending actions on main server thread (call this from main thread)
     */
    public static void processPendingActions() {
        Runnable action;
        int processed = 0;
        while ((action = pendingActions.poll()) != null && processed < 1000) { // Limit to prevent lag
            action.run();
            processed++;
        }
    }
    
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
        
        // ULTRA-FAST batch processing - queue actions for main thread execution
        int queued = 0;
        
        for (int i = 0; i < actionCount; i++) {
            if (buf.remaining() < 12) break;
            
            int agentId = buf.getInt();
            short angleRaw = buf.getShort();
            short flags = buf.getShort();
            buf.getInt(); // skip reserved bytes
            
            // Queue actions for main thread execution to prevent ConcurrentModificationException
            if ((flags & 2) != 0) { // Move flag
                float angle = (angleRaw & 0xFFFF) * 360.0f / 65535.0f;
                pendingActions.offer(() -> {
                    RLOperator op = AgentIdManager.getById(agentId);
                    if (op != null) {
                        op.moveTowards(angle, 0.13f);
                    }
                });
                queued++;
            }
            
            if ((flags & 1) != 0) { // Fire flag
                pendingActions.offer(() -> {
                    RLOperator op = AgentIdManager.getById(agentId);
                    if (op != null) {
                        op.shootForward();
                    }
                });
                queued++;
            }
        }
        
        // Only log every 10th batch to reduce console spam
        if (queued > 0 && (System.currentTimeMillis() % 500) < 50) {
            System.out.println("⚡ Queued " + queued + " actions from " + actionCount + " agents");
        }
    }
}
