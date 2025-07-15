package com.dyllan.minekov;

import com.dyllan.minekov.VectorizedActionDecoder.AgentAction;
import com.dyllan.minekov.entities.RLOperator;
import com.google.gson.JsonObject;

import java.net.URI;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Game-specific controller that handles RL agent actions.
 * Uses the generic WebSocketClient for network communication.
 * Supports both JSON (legacy) and ultra-efficient binary protocols.
 */
public class PythonRLController {

    private final WebSocketClient webSocket;
    public static final Queue<Map<Integer, AgentAction>> ACTION_QUEUE = new ConcurrentLinkedQueue<>();

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

    private void handleBinaryMessage(byte[] data) {
        // This will be used by the WebSocketClient to prepare actions by putting them in the ACTION_QUEUE for us to
        // process in the main tick loop.
        Map<Integer, AgentAction> actions = VectorizedActionDecoder.decodeActions(data);
        if (actions != null && !actions.isEmpty()) {
            ACTION_QUEUE.add(actions);  // Thread-safe enqueue
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
