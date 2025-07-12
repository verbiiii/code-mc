package com.dyllan.minekov;

import com.dyllan.minekov.entities.RLOperator;
import com.dyllan.minekov.entities.RLOperatorRegistry;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

public class PythonWebSocketClient {

    private final URI uri;
    private Channel channel;
    private final EventLoopGroup group = new NioEventLoopGroup();
    private final Gson gson = new Gson();

    public PythonWebSocketClient(URI uri) {
        this.uri = uri;
    }

    public void connect() {
        try {
            String host = uri.getHost();
            int port = uri.getPort() == -1 ? 80 : uri.getPort();
            String path = uri.getPath().isEmpty() ? "/" : uri.getPath();

            byte[] nonce = new byte[16];
            new SecureRandom().nextBytes(nonce);
            String secKey = Base64.getEncoder().encodeToString(nonce);

            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                     .channel(NioSocketChannel.class)
                     .handler(new ChannelInitializer<>() {
                         @Override
                         protected void initChannel(Channel ch) {
                             ch.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                                 private boolean handshakeComplete = false;

                                 @Override
                                 public void channelActive(ChannelHandlerContext ctx) {
                                     String request =
                                             "GET " + path + " HTTP/1.1\r\n" +
                                             "Host: " + host + "\r\n" +
                                             "Upgrade: websocket\r\n" +
                                             "Connection: Upgrade\r\n" +
                                             "Sec-WebSocket-Key: " + secKey + "\r\n" +
                                             "Sec-WebSocket-Version: 13\r\n" +
                                             "\r\n";
                                     ctx.writeAndFlush(Unpooled.copiedBuffer(request, StandardCharsets.US_ASCII));
                                 }

                                 @Override
                                 protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {

                                     int len = msg.readableBytes();
                                     byte[] raw = new byte[len];
                                     msg.getBytes(msg.readerIndex(), raw);

                                    if (raw.length < 2) {
                                        System.err.println("⚠️ Received too-short frame: " + raw.length + " bytes");
                                        return;
                                    }

                                     if (!handshakeComplete) {
                                         String response = new String(raw, StandardCharsets.US_ASCII);
                                         if (response.contains("101 Switching Protocols")) {
                                             // Silent connection success - no console spam
                                             handshakeComplete = true;
                                             send("{\"type\": \"hello\", \"msg\": \"Java online\"}");
                                         } else {
                                             // Silent handshake failure - no console spam
                                             ctx.close();
                                         }
                                         return;
                                     }

                                     byte opcode = (byte) (raw[0] & 0x0F);
                                     int payloadLen = raw[1] & 0x7F;
                                     int offset = 2;

                                     if (opcode == 0x9) {
                                         byte[] payload = new byte[payloadLen];
                                         System.arraycopy(raw, offset, payload, 0, payloadLen);
                                         byte[] mask = new byte[4];
                                         new SecureRandom().nextBytes(mask);
                                         ByteBuf pong = Unpooled.buffer();
                                         pong.writeByte(0x8A);
                                         pong.writeByte(0x80 | payloadLen);
                                         pong.writeBytes(mask);
                                         for (int i = 0; i < payloadLen; i++) {
                                             pong.writeByte(payload[i] ^ mask[i % 4]);
                                         }
                                         ctx.writeAndFlush(pong);
                                         return;
                                     }

                                     if (opcode == 0x8) {
                                        //  int code = (payloadLen >= 2)
                                        //          ? ((raw[offset] & 0xFF) << 8) | (raw[offset + 1] & 0xFF)
                                        //          : 1000;
                                        //  String reason = (payloadLen > 2)
                                        //          ? new String(raw, offset + 2, payloadLen - 2, StandardCharsets.UTF_8)
                                        //          : "(no reason)";
                                        //  System.out.println("❌ Close frame received: code=" + code + ", reason=" + reason);
                                         ctx.close();
                                         return;
                                     }

                                    if (opcode == 0x1) { // Text frame
                                        // Parse payload length properly (including extended lengths)
                                        int actualPayloadLen = payloadLen;
                                        if (payloadLen == 126) {
                                            // Next 2 bytes contain the actual length
                                            if (raw.length < offset + 2) {
                                                System.err.println("⚠️ Incomplete extended length frame");
                                                return;
                                            }
                                            actualPayloadLen = ((raw[offset] & 0xFF) << 8) | (raw[offset + 1] & 0xFF);
                                            offset += 2;
                                        } else if (payloadLen == 127) {
                                            // Next 8 bytes contain the actual length (we'll limit to 4 bytes for safety)
                                            if (raw.length < offset + 8) {
                                                System.err.println("⚠️ Incomplete 64-bit length frame");
                                                return;
                                            }
                                            // Only use the last 4 bytes for length (first 4 should be 0 for reasonable sizes)
                                            actualPayloadLen = ((raw[offset + 4] & 0xFF) << 24) | 
                                                             ((raw[offset + 5] & 0xFF) << 16) | 
                                                             ((raw[offset + 6] & 0xFF) << 8) | 
                                                             (raw[offset + 7] & 0xFF);
                                            offset += 8;
                                        }
                                        
                                        // Bounds check
                                        if (actualPayloadLen < 0 || actualPayloadLen > raw.length - offset) {
                                            System.err.println("⚠️ Invalid payload length: " + actualPayloadLen + " (frame size: " + raw.length + ", offset: " + offset + ")");
                                            return;
                                        }
                                        
                                        // Extract payload
                                        byte[] payload = new byte[actualPayloadLen];
                                        System.arraycopy(raw, offset, payload, 0, actualPayloadLen);
                                        String rawText = new String(payload, StandardCharsets.UTF_8);

                                        // Process JSON
                                        try {
                                            JsonObject obj = gson.fromJson(rawText, JsonObject.class);
                                            if (obj == null || !obj.has("type")) return;

                                            String type = obj.get("type").getAsString();

                                            if ("actions_batch".equals(type)) {
                                                // Handle batched actions
                                                if (obj.has("actions")) {
                                                    var actionsArray = obj.getAsJsonArray("actions");
                                                    System.out.println("📦 Processing batch with " + actionsArray.size() + " actions");
                                                    for (var actionElement : actionsArray) {
                                                        JsonObject action = actionElement.getAsJsonObject();
                                                        processAction(action);
                                                    }
                                                }
                                            } else {
                                                // Handle individual actions (for backward compatibility)
                                                processAction(obj);
                                            }
                                        } catch (Exception e) {
                                            System.err.println("❌ Failed to parse JSON (length: " + rawText.length() + "):");
                                            System.err.println("✂️ JSON preview: " + (rawText.length() > 200 ? rawText.substring(0, 200) + "..." : rawText));
                                            e.printStackTrace();
                                        }
                                    }



                                 }

                                 @Override
                                 public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                     // Only log non-connection errors to avoid spam
                                     if (!(cause instanceof java.net.ConnectException) && 
                                         !(cause.getCause() instanceof java.net.ConnectException)) {
                                         System.err.println("❗ WebSocket error:");
                                         cause.printStackTrace();
                                     }
                                     ctx.close();
                                 }

                                 @Override
                                 public void channelInactive(ChannelHandlerContext ctx) {
                                     // Silent disconnect - no console spam
                                 }
                             });
                         }
                     });

            channel = bootstrap.connect(host, port).sync().channel();

        } catch (Exception e) {
            // Silent connection failure - no console spam
            // The exception will be caught by the exceptionCaught handler above
        }
    }

    private void processAction(JsonObject obj) {
        if (obj == null || !obj.has("type")) return;

        String type = obj.get("type").getAsString();
        String id = obj.has("id") ? obj.get("id").getAsString() : null;

        if (id == null) return;

        // 🚦 Thread-safe operator lookup to prevent ConcurrentModificationException
        try {
            for (RLOperator op : RLOperatorRegistry.getAll().toArray(new RLOperator[0])) {
                if (!op.getUUID().toString().equals(id)) continue;

                switch (type) {
                    case "joystick_vector" -> {
                        JsonObject vector = obj.getAsJsonObject("vector");
                        if (vector != null && vector.has("angle")) {
                            float angle = vector.get("angle").getAsFloat();
                            
                            // 🎯 Execute movement directly on main thread
                            op.moveTowards(angle, 0.13f);
                            System.out.println("🕹️ Moving operator " + id.substring(0, 8) + " → angle=" + angle + "°");
                        }
                    }
                    case "fire" -> {
                        // 🎯 Execute firing directly on main thread
                        op.shootForward();
                        System.out.println("🔫 Operator " + id.substring(0, 8) + " fired!");
                    }
                }
                break; // operator found
            }
        } catch (Exception e) {
            // Silently handle concurrent modification issues
            System.err.println("⚠️ Entity access issue: " + e.getMessage());
        }
    }

    public void shutdown() {
        try {
            if (channel != null) channel.close().sync();
        } catch (Exception ignored) {}
        group.shutdownGracefully();
    }

    public boolean isConnected() {
        return channel != null && channel.isOpen();
    }

    public void send(String message) {
        if (!isConnected()) return;

        byte[] payload = message.getBytes(StandardCharsets.UTF_8);
        byte[] mask = new byte[4];
        new SecureRandom().nextBytes(mask);

        ByteBuf frame = Unpooled.buffer();
        frame.writeByte(0x81);

        int payloadLen = payload.length;
        if (payloadLen <= 125) {
            frame.writeByte(0x80 | payloadLen);
        } else if (payloadLen <= 0xFFFF) {
            frame.writeByte(0x80 | 126);
            frame.writeShort(payloadLen);
        } else {
            frame.writeByte(0x80 | 127);
            frame.writeLong(payloadLen);
        }

        frame.writeBytes(mask);
        for (int i = 0; i < payloadLen; i++) {
            frame.writeByte(payload[i] ^ mask[i % 4]);
        }

        channel.writeAndFlush(frame);
    }
}
