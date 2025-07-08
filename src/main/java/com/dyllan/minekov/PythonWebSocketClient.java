package com.dyllan.minekov;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import com.dyllan.minekov.entities.RLOperator;
import com.dyllan.minekov.entities.RLOperatorRegistry;

public class PythonWebSocketClient {

    private final Gson gson = new Gson();
    private final URI uri;
    private Channel channel;
    private final EventLoopGroup group = new NioEventLoopGroup();

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

                                     System.out.println("📡 Raw frame (" + len + " bytes):");
                                     System.out.println(bytesToHex(raw));
                                     System.out.println(new String(raw, StandardCharsets.UTF_8).replaceAll("[^\\x20-\\x7E]", "."));

                                     if (!handshakeComplete) {
                                         String response = new String(raw, StandardCharsets.US_ASCII);
                                         if (response.contains("101 Switching Protocols")) {
                                             System.out.println("✅ WebSocket connected to Python backend.");
                                             handshakeComplete = true;
                                             send("{\"type\": \"hello\", \"msg\": \"Java online\"}");
                                         } else {
                                             System.err.println("❌ Handshake failed:\n" + response);
                                             ctx.close();
                                         }
                                         return;
                                     }

                                     byte opcode = (byte) (raw[0] & 0x0F);
                                     int payloadLen = raw[1] & 0x7F;
                                     int offset = 2;

                                     if (opcode == 0x9) { // Ping
                                         System.out.println("📶 Ping received — sending Pong.");
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

                                     if (opcode == 0x8) { // Close
                                         int code = (payloadLen >= 2)
                                                 ? ((raw[offset] & 0xFF) << 8) | (raw[offset + 1] & 0xFF)
                                                 : 1000;
                                         String reason = (payloadLen > 2)
                                                 ? new String(raw, offset + 2, payloadLen - 2, StandardCharsets.UTF_8)
                                                 : "(no reason)";
                                         System.out.println("❌ Close frame received: code=" + code + ", reason=" + reason);
                                         ctx.close();
                                         return;
                                     }

                                     if (opcode == 0x1) { // Text
                                         if (len >= offset + payloadLen) {
                                             String text = new String(raw, offset, payloadLen, StandardCharsets.UTF_8);
                                             System.out.println("📩 Received command: " + text);

                                             try {
                                                 JsonObject json = gson.fromJson(text, JsonObject.class);
                                                 if (json == null || !json.has("type")) return;

                                                 if ("joystick_vector".equals(json.get("type").getAsString())) {
                                                     String id = json.get("id").getAsString();
                                                     JsonObject vector = json.getAsJsonObject("vector");

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

                                             } catch (Exception e) {
                                                 System.err.println("❌ Failed to parse joystick_vector:");
                                                 e.printStackTrace();
                                             }

                                         } else {
                                             System.out.println("⚠️ Text frame too short");
                                         }
                                     }

                                     if (opcode == 0x2) {
                                         System.out.println("📦 Binary frame received, length: " + payloadLen);
                                     }

                                     System.out.println("🔍 Unhandled opcode: " + Integer.toHexString(opcode));
                                 }

                                 private static String bytesToHex(byte[] bytes) {
                                     StringBuilder sb = new StringBuilder();
                                     for (int i = 0; i < bytes.length; i++) {
                                         sb.append(String.format("%02X ", bytes[i]));
                                         if ((i + 1) % 16 == 0) sb.append("\n");
                                     }
                                     return sb.toString();
                                 }

                                 @Override
                                 public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                     System.err.println("❗ WebSocket error:");
                                     cause.printStackTrace();
                                     ctx.close();
                                 }

                                 @Override
                                 public void channelInactive(ChannelHandlerContext ctx) {
                                     System.out.println("🔌 WebSocket closed.");
                                 }
                             });
                         }
                     });

            channel = bootstrap.connect(host, port).sync().channel();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void shutdown() {
        try {
            if (channel != null) channel.close().sync();
        } catch (Exception ignored) {}
        group.shutdownGracefully();
    }

    public boolean isConnected() {
        if (channel == null || !channel.isActive()) return false;
        try {
            channel.writeAndFlush(Unpooled.EMPTY_BUFFER).sync();
            return true;
        } catch (Exception e) {
            return false;
        }
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
