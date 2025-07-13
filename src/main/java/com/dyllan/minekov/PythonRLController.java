package com.dyllan.minekov;

import com.dyllan.minekov.entities.RLOperator;
import com.dyllan.minekov.entities.RLOperatorRegistry;
import com.google.gson.JsonObject;

import java.net.URI;
import java.util.Map;

/**
 * Game-specific controller that handles RL agent actions.
 * Uses the generic WebSocketClient for network communication.
 * Supports both JSON (legacy) and ultra-efficient binary protocols.
 */
public class PythonRLController {

    private final WebSocketClient webSocket;

    public PythonRLController(URI uri) {
        this.webSocket = new WebSocketClient(uri);
        
        // Handle both JSON and binary messages
        this.webSocket.setMessageHandler(this::handleMessage);
        this.webSocket.setBinaryMessageHandler(this::handleBinaryMessage);
    }

    public void connect() {
        webSocket.connect();
    }

    public void shutdown() {
        webSocket.shutdown();
    }

    public boolean isConnected() {
        return webSocket.isConnected();
    }

    /**
     * Handle incoming JSON messages from Python
     */
    private void handleMessage(JsonObject obj) {
        if (obj == null || !obj.has("type")) return;

        String type = obj.get("type").getAsString();

        switch (type) {
            case "actions_batch" -> handleActionsBatch(obj);
            case "joystick_vector", "fire" -> handleSingleAction(obj);
            case "ping" -> handlePing(obj);
            default -> {
                // Silently ignore unknown message types
            }
        }
    }

    /**
     * Handle incoming binary messages from Python (ultra-efficient)
     */
    private void handleBinaryMessage(byte[] data) {
        // Use new vectorized decoder for better performance
        Map<Integer, VectorizedActionDecoder.AgentAction> actions = VectorizedActionDecoder.decodeActions(data);
        
        // Get all RL operators in same order as TrainingState (sequential from collection)
        RLOperator[] rlOperators = RLOperatorRegistry.getAll().stream()
            .toArray(RLOperator[]::new);
        
        // Apply actions using sequential indices
        for (Map.Entry<Integer, VectorizedActionDecoder.AgentAction> entry : actions.entrySet()) {
            int sequentialIndex = entry.getKey();
            VectorizedActionDecoder.AgentAction action = entry.getValue();
            
            // Apply action to operator at this sequential index
            if (sequentialIndex < rlOperators.length) {
                RLOperator op = rlOperators[sequentialIndex];
                
                // Apply the action using existing methods
                if (action.walk) {
                    op.moveTowards(action.angle, 0.13f);
                }
                
                if (action.shoot) {
                    op.shootForward();
                }
            }
        }
    }

    /**
     * Handle batched actions from Python
     */
    private void handleActionsBatch(JsonObject obj) {
        if (!obj.has("actions")) return;

        try {
            var actionsArray = obj.getAsJsonArray("actions");
            System.out.println("📦 Processing batch with " + actionsArray.size() + " actions");
            
            for (var actionElement : actionsArray) {
                JsonObject action = actionElement.getAsJsonObject();
                processAction(action);
            }
        } catch (Exception e) {
            System.err.println("⚠️ RLController: Batch processing error: " + e.getMessage());
        }
    }

    /**
     * Handle individual actions (for backward compatibility)
     */
    private void handleSingleAction(JsonObject obj) {
        processAction(obj);
    }

    /**
     * Handle ping messages from Python
     */
    private void handlePing(JsonObject obj) {
        // Respond to pings if needed
        String msg = obj.has("msg") ? obj.get("msg").getAsString() : "ping";
        System.out.println("🏓 Received ping: " + msg);
    }

    /**
     * Process a single action (move or fire) - LEGACY JSON ONLY
     * Binary actions are handled by BinaryActionDecoder
     */
    private void processAction(JsonObject obj) {
        if (obj == null || !obj.has("type")) return;

        String type = obj.get("type").getAsString();
        String id = obj.has("id") ? obj.get("id").getAsString() : null;

        if (id == null) return;

        // For JSON messages, ID might still be UUID string, need to find by UUID
        try {
            for (RLOperator op : RLOperatorRegistry.getAll().toArray(new RLOperator[0])) {
                if (!op.getUUID().toString().equals(id)) continue;

                switch (type) {
                    case "joystick_vector" -> {
                        JsonObject vector = obj.getAsJsonObject("vector");
                        if (vector != null && vector.has("angle")) {
                            float angle = vector.get("angle").getAsFloat();
                            op.moveTowards(angle, 0.13f);
                        }
                    }
                    case "fire" -> {
                        op.shootForward();
                    }
                }
                break; // operator found
            }
        } catch (Exception e) {
            System.err.println("⚠️ RLController: JSON action processing error: " + e.getMessage());
        }
    }

    /**
     * Send a JSON message to Python
     */
    public void sendToPython(JsonObject message) {
        webSocket.sendJson(message);
    }

    /**
     * Send a text message to Python
     */
    public void sendToPython(String message) {
        webSocket.sendText(message);
    }

    /**
     * Send binary data to Python (ultra-fast vectorized protocol)
     */
    public void sendBinaryToPython(byte[] data) {
        webSocket.sendBinary(data);
    }
}
