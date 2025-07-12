package com.dyllan.minekov;

import com.dyllan.minekov.entities.RLOperator;
import com.dyllan.minekov.entities.RLOperatorRegistry;
import com.google.gson.JsonObject;

import java.net.URI;

/**
 * Game-specific controller that handles RL agent actions.
 * Uses the generic WebSocketClient for network communication.
 */
public class PythonRLController {

    private final WebSocketClient webSocket;

    public PythonRLController(URI uri) {
        this.webSocket = new WebSocketClient(uri);
        this.webSocket.setMessageHandler(this::handleMessage);
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
     * Process a single action (move or fire)
     */
    private void processAction(JsonObject obj) {
        if (obj == null || !obj.has("type")) return;

        String type = obj.get("type").getAsString();
        String id = obj.has("id") ? obj.get("id").getAsString() : null;

        if (id == null) return;

        // Thread-safe operator lookup
        try {
            for (RLOperator op : RLOperatorRegistry.getAll().toArray(new RLOperator[0])) {
                if (!op.getUUID().toString().equals(id)) continue;

                switch (type) {
                    case "joystick_vector" -> {
                        JsonObject vector = obj.getAsJsonObject("vector");
                        if (vector != null && vector.has("angle")) {
                            float angle = vector.get("angle").getAsFloat();
                            op.moveTowards(angle, 0.13f);
                            // Uncomment for debug: System.out.println("🕹️ Moving operator " + id.substring(0, 8) + " → angle=" + angle + "°");
                        }
                    }
                    case "fire" -> {
                        op.shootForward();
                        // Uncomment for debug: System.out.println("🔫 Operator " + id.substring(0, 8) + " fired!");
                    }
                }
                break; // operator found
            }
        } catch (Exception e) {
            System.err.println("⚠️ RLController: Entity access issue: " + e.getMessage());
        }
    }

    /**
     * Send a message to Python
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
}
