package com.dyllan.minekov;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class PythonBridge {
    public static void sendSceneVolume(byte[] volume) {
        try {
            URL url = new URL("http://127.0.0.1:8050/scene");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(volume);
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                System.err.println("[PythonBridge] Server responded with code: " + responseCode);
            } else {
                System.out.println("[PythonBridge] Scene volume sent successfully.");
            }
        } catch (Exception e) {
            System.err.println("[PythonBridge] Failed to send scene volume: " + e.getMessage());
        }
    }
}
