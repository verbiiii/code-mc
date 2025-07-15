package com.dyllan.minekov;

import com.dyllan.minekov.entities.RLOperator;
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
     * Handle incoming binary messages from Python (ultra-efficient)
     */
    private void handleBinaryMessage(byte[] data) {
        // Use new vectorized decoder for better performance
        Map<Integer, VectorizedActionDecoder.AgentAction> actions = VectorizedActionDecoder.decodeActions(data);
        
        // Apply actions using sequential indices
        for (Map.Entry<Integer, VectorizedActionDecoder.AgentAction> entry : actions.entrySet()) {
            int sequentialIndex = entry.getKey();
            VectorizedActionDecoder.AgentAction action = entry.getValue();

            RLOperator operator = Minekov.trainingState.getRLOperator(sequentialIndex);
            if (operator == null) {
                throw new IllegalStateException("No RLOperator found for index: " + sequentialIndex);
            }
            
            action.performAction(operator);
        }
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
