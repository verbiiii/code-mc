package com.dyllan.minekov;

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
import java.util.function.Consumer;

/**
 * Generic WebSocket client that handles pure WebSocket communication.
 * Separates networking logic from game-specific logic.
 */
public class WebSocketClient {

    private final URI uri;
    private Channel channel;
    private final EventLoopGroup group = new NioEventLoopGroup();
    private final Gson gson = new Gson();
    private Consumer<JsonObject> messageHandler;

    public WebSocketClient(URI uri) {
        this.uri = uri;
    }

    public void setMessageHandler(Consumer<JsonObject> handler) {
        this.messageHandler = handler;
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
                                         System.err.println("⚠️ WebSocket: Received too-short frame: " + raw.length + " bytes");
                                         return;
                                     }

                                     if (!handshakeComplete) {
                                         String response = new String(raw, StandardCharsets.US_ASCII);
                                         if (response.contains("101 Switching Protocols")) {
                                             handshakeComplete = true;
                                             JsonObject hello = new JsonObject();
                                             hello.addProperty("type", "hello");
                                             hello.addProperty("msg", "Java WebSocket online");
                                             sendJson(hello);
                                         } else {
                                             ctx.close();
                                         }
                                         return;
                                     }

                                     // Parse WebSocket frame
                                     byte opcode = (byte) (raw[0] & 0x0F);
                                     int payloadLen = raw[1] & 0x7F;
                                     int offset = 2;

                                     // Handle ping frames
                                     if (opcode == 0x9) {
                                         sendPong(raw, offset, payloadLen, ctx);
                                         return;
                                     }

                                     // Handle close frames
                                     if (opcode == 0x8) {
                                         ctx.close();
                                         return;
                                     }

                                     // Handle text frames
                                     if (opcode == 0x1) {
                                         String jsonText = parseTextFrame(raw, payloadLen, offset);
                                         if (jsonText != null) {
                                             handleJsonMessage(jsonText);
                                         }
                                     }
                                 }

                                 private String parseTextFrame(byte[] raw, int payloadLen, int offset) {
                                     try {
                                         // Parse extended payload lengths
                                         int actualPayloadLen = payloadLen;
                                         if (payloadLen == 126) {
                                             if (raw.length < offset + 2) return null;
                                             actualPayloadLen = ((raw[offset] & 0xFF) << 8) | (raw[offset + 1] & 0xFF);
                                             offset += 2;
                                         } else if (payloadLen == 127) {
                                             if (raw.length < offset + 8) return null;
                                             actualPayloadLen = ((raw[offset + 4] & 0xFF) << 24) |
                                                               ((raw[offset + 5] & 0xFF) << 16) |
                                                               ((raw[offset + 6] & 0xFF) << 8) |
                                                               (raw[offset + 7] & 0xFF);
                                             offset += 8;
                                         }

                                         // Bounds check
                                         if (actualPayloadLen < 0 || actualPayloadLen > raw.length - offset) {
                                             System.err.println("⚠️ WebSocket: Invalid payload length: " + actualPayloadLen +
                                                     " (frame size: " + raw.length + ", offset: " + offset + ")");
                                             return null;
                                         }

                                         // Extract payload
                                         byte[] payload = new byte[actualPayloadLen];
                                         System.arraycopy(raw, offset, payload, 0, actualPayloadLen);
                                         return new String(payload, StandardCharsets.UTF_8);

                                     } catch (Exception e) {
                                         System.err.println("⚠️ WebSocket: Frame parsing error: " + e.getMessage());
                                         return null;
                                     }
                                 }

                                 private void sendPong(byte[] raw, int offset, int payloadLen, ChannelHandlerContext ctx) {
                                     try {
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
                                     } catch (Exception e) {
                                         System.err.println("⚠️ WebSocket: Pong error: " + e.getMessage());
                                     }
                                 }

                                 private void handleJsonMessage(String jsonText) {
                                     try {
                                         JsonObject obj = gson.fromJson(jsonText, JsonObject.class);
                                         if (obj != null && messageHandler != null) {
                                             messageHandler.accept(obj);
                                         }
                                     } catch (Exception e) {
                                         System.err.println("⚠️ WebSocket: JSON parse error for message length " + jsonText.length());
                                         // Silently ignore malformed JSON to prevent spam
                                     }
                                 }

                                 @Override
                                 public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                     if (!(cause instanceof java.net.ConnectException) &&
                                         !(cause.getCause() instanceof java.net.ConnectException)) {
                                         System.err.println("⚠️ WebSocket: Network error: " + cause.getMessage());
                                     }
                                     ctx.close();
                                 }

                                 @Override
                                 public void channelInactive(ChannelHandlerContext ctx) {
                                     // Silent disconnect
                                 }
                             });
                         }
                     });

            channel = bootstrap.connect(host, port).sync().channel();

        } catch (Exception e) {
            System.err.println("⚠️ WebSocket: Connection failed: " + e.getMessage());
        }
    }

    public void sendJson(JsonObject json) {
        if (!isConnected()) return;
        sendText(gson.toJson(json));
    }

    public void sendText(String message) {
        if (!isConnected()) return;

        try {
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
        } catch (Exception e) {
            System.err.println("⚠️ WebSocket: Send error: " + e.getMessage());
        }
    }

    public boolean isConnected() {
        return channel != null && channel.isOpen();
    }

    public void shutdown() {
        try {
            if (channel != null) channel.close().sync();
        } catch (Exception ignored) {}
        group.shutdownGracefully();
    }
}
