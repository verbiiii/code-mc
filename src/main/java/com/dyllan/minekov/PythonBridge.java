package com.dyllan.minekov;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import com.dyllan.minekov.scene.SceneEncoder;

public class PythonBridge {
    public static void sendSceneVolume(SceneEncoder encoder) {
        try {
            URL url = new URL("http://127.0.0.1:8050/scene");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/octet-stream");

            // 🔥 Auto-generate shape header from encoder
            String shapeHeader = encoder.getSizeX() + "," + encoder.getSizeY() + "," + encoder.getSizeZ();
            conn.setRequestProperty("X-Scene-Shape", shapeHeader);

            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(encoder.getBuffer());
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                System.err.println("[PythonBridge] Server responded with code: " + responseCode);
            } else {
                System.out.println("[PythonBridge] Scene volume sent successfully.");
            }

            conn.disconnect();
        } catch (Exception e) {
            System.err.println("[PythonBridge] Failed to send scene volume: " + e.getMessage());
        }
    }
}
