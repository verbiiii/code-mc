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
                                        String rawText = msg.toString(StandardCharsets.UTF_8);

                                        // Strip anything before the first valid JSON
                                        int firstBrace = rawText.indexOf('{');
                                        if (firstBrace == -1) {
                                            System.err.println("⚠️ No valid JSON object in frame: " + rawText);
                                            return;
                                        }
                                        rawText = rawText.substring(firstBrace);

                                        // Split multiple JSON objects stuck together
                                        String[] parts = rawText.split("}(?=\\{)"); // lookahead to preserve both } and {
                                        for (String part : parts) {
                                            String json = part.endsWith("}") ? part : part + "}";
                                            try {
                                                JsonObject obj = null;
                                                try {
                                                    obj = gson.fromJson(json, JsonObject.class);
                                                    if (obj == null || !obj.has("type")) continue;

                                                    if ("joystick_vector".equals(obj.get("type").getAsString())) {
                                                        String id = obj.get("id").getAsString();
                                                        JsonObject vector = obj.getAsJsonObject("vector");

                                                        if (vector != null && vector.has("angle")) {
                                                            float angle = vector.get("angle").getAsFloat();
                                                            for (RLOperator op : RLOperatorRegistry.getAll()) {
                                                                if (op.getUUID().toString().equals(id)) {
                                                                    op.moveTowards(angle, 0.13f);
                                                                    System.out.println("🕹 Moving operator " + id + " → angle=" + angle);
                                                                    break;
                                                                }
                                                            }
                                                        }
                                                    }
                                                } catch (com.google.gson.JsonSyntaxException ex) {
                                                    // Silent JSON parsing error - no console spam
                                                }

                                                if (obj == null || !obj.has("type")) continue;

                                                String type = obj.get("type").getAsString();
                                                String id = obj.has("id") ? obj.get("id").getAsString() : null;

                                                if (id == null) return;

                                                for (RLOperator op : RLOperatorRegistry.getAll()) {
                                                    if (!op.getUUID().toString().equals(id)) continue;

                                                    switch (type) {
                                                        case "joystick_vector" -> {
                                                            JsonObject vector = obj.getAsJsonObject("vector");
                                                            if (vector != null && vector.has("angle")) {
                                                                float angle = vector.get("angle").getAsFloat();
                                                                op.moveTowards(angle, 0.13f);
                                                                // Silent movement - no console spam
                                                            }
                                                        }
                                                        case "fire" -> {
                                                            op.shootForward();
                                                            // Silent fire - no console spam
                                                        }
                                                    }
                                                    break; // operator found
                                                }
                                            } catch (Exception e) {
                                                System.err.println("❌ Failed to parse joystick_vector:");
                                                System.err.println("✂️ JSON was: " + json);
                                                e.printStackTrace();
                                            }
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
