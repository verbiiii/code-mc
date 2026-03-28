package com.verbii.minekov;

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
    private Consumer<JsonObject> messageJSONHandler;
    private Consumer<byte[]> binaryMessageHandler;
    private Runnable onConnectedCallback;

    public WebSocketClient(URI uri) {
        this.uri = uri;
    }

    public void setMessageJSONHandler(Consumer<JsonObject> handler) {
        this.messageJSONHandler = handler;
    }

    public void setBinaryMessageHandler(Consumer<byte[]> handler) {
        this.binaryMessageHandler = handler;
    }

    public void setOnConnectedCallback(Runnable callback) {
        this.onConnectedCallback = callback;
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
                                 private ByteBuf frameBuffer = Unpooled.buffer(); // Buffer for partial frames

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
                                     // Add incoming data to buffer
                                     frameBuffer.writeBytes(msg);
                                     
                                     // Process complete frames from buffer
                                     processFrameBuffer(ctx);
                                 }
                                 
                                 private void processFrameBuffer(ChannelHandlerContext ctx) {
                                     while (frameBuffer.readableBytes() > 0) {
                                         int startReadIndex = frameBuffer.readerIndex();
                                         
                                         // Try to read a complete frame
                                         if (!processCompleteFrame(ctx)) {
                                             // Not enough data for complete frame, reset and wait for more
                                             frameBuffer.readerIndex(startReadIndex);
                                             break;
                                         }
                                     }
                                 }
                                 
                                 private boolean processCompleteFrame(ChannelHandlerContext ctx) {
                                     if (frameBuffer.readableBytes() < 2) return false; // Need at least 2 bytes
                                     
                                     // Handle HTTP handshake response first
                                     if (!handshakeComplete) {
                                         // Look for end of HTTP response
                                         int available = frameBuffer.readableBytes();
                                         byte[] buffer = new byte[available];
                                         frameBuffer.getBytes(frameBuffer.readerIndex(), buffer);
                                         String response = new String(buffer, StandardCharsets.US_ASCII);
                                         
                                         int endOfHeaders = response.indexOf("\r\n\r\n");
                                         if (endOfHeaders == -1) return false; // Need complete HTTP response
                                         
                                         if (response.contains("101 Switching Protocols")) {
                                             handshakeComplete = true;
                                             JsonObject hello = new JsonObject();
                                             hello.addProperty("type", "hello");
                                             hello.addProperty("msg", "Java WebSocket online");
                                             sendJson(hello);
                                             if (onConnectedCallback != null) onConnectedCallback.run();
                                         } else {
                                             ctx.close();
                                             return false;
                                         }
                                         
                                         // Skip the HTTP response
                                         frameBuffer.skipBytes(endOfHeaders + 4);
                                         return true;
                                     }
                                     
                                     // Parse WebSocket frame header
                                     if (frameBuffer.readableBytes() < 2) return false;
                                     
                                     byte firstByte = frameBuffer.getByte(frameBuffer.readerIndex());
                                     byte secondByte = frameBuffer.getByte(frameBuffer.readerIndex() + 1);
                                     
                                     byte opcode = (byte) (firstByte & 0x0F);
                                     int payloadLen = secondByte & 0x7F;
                                     int headerSize = 2;
                                     
                                     // Calculate actual payload length and total frame size
                                     int actualPayloadLen = payloadLen;
                                     if (payloadLen == 126) {
                                         if (frameBuffer.readableBytes() < 4) return false;
                                         actualPayloadLen = frameBuffer.getUnsignedShort(frameBuffer.readerIndex() + 2);
                                         headerSize = 4;
                                     } else if (payloadLen == 127) {
                                         if (frameBuffer.readableBytes() < 10) return false;
                                         // For simplicity, only handle lower 32 bits
                                         actualPayloadLen = frameBuffer.getInt(frameBuffer.readerIndex() + 6);
                                         headerSize = 10;
                                     }
                                     
                                     int totalFrameSize = headerSize + actualPayloadLen;
                                     
                                     // Check if we have the complete frame
                                     if (frameBuffer.readableBytes() < totalFrameSize) {
                                         return false; // Wait for more data
                                     }
                                     
                                     // Extract the complete frame
                                     byte[] frameData = new byte[totalFrameSize];
                                     frameBuffer.readBytes(frameData);
                                     
                                     // Handle ping frames
                                     if (opcode == 0x9) {
                                         sendPong(frameData, headerSize, actualPayloadLen, ctx);
                                         return true;
                                     }

                                     // Handle close frames
                                     if (opcode == 0x8) {
                                         ctx.close();
                                         return true;
                                     }

                                     // Handle text frames
                                     if (opcode == 0x1) {
                                         String jsonText = parseTextFrame(frameData, headerSize, actualPayloadLen);
                                         if (jsonText != null) {
                                             handleJsonMessage(jsonText);
                                         }
                                         return true;
                                     }

                                     // Handle binary frames
                                     if (opcode == 0x2) {
                                         byte[] binaryData = parseBinaryFrame(frameData, headerSize, actualPayloadLen);
                                         if (binaryData != null && binaryMessageHandler != null) {
                                             binaryMessageHandler.accept(binaryData);
                                         }
                                         return true;
                                     }
                                     
                                     // Unknown frame type, skip it
                                     return true;
                                 }

                                 private String parseTextFrame(byte[] frameData, int headerSize, int payloadLen) {
                                     try {
                                         // Extract payload directly using known header size and payload length
                                         if (payloadLen < 0 || headerSize + payloadLen > frameData.length) {
                                             System.err.println("⚠️ WebSocket: Invalid text frame - payload: " + payloadLen + 
                                                              ", header: " + headerSize + ", total: " + frameData.length);
                                             return null;
                                         }

                                         byte[] payload = new byte[payloadLen];
                                         System.arraycopy(frameData, headerSize, payload, 0, payloadLen);
                                         return new String(payload, StandardCharsets.UTF_8);

                                     } catch (Exception e) {
                                         System.err.println("⚠️ WebSocket: Text frame parsing error: " + e.getMessage());
                                         return null;
                                     }
                                 }

                                 private byte[] parseBinaryFrame(byte[] frameData, int headerSize, int payloadLen) {
                                     try {
                                         // Extract payload directly using known header size and payload length
                                         if (payloadLen < 0 || headerSize + payloadLen > frameData.length) {
                                             System.err.println("⚠️ WebSocket: Invalid binary frame - payload: " + payloadLen + 
                                                              ", header: " + headerSize + ", total: " + frameData.length);
                                             return null;
                                         }

                                         byte[] payload = new byte[payloadLen];
                                         System.arraycopy(frameData, headerSize, payload, 0, payloadLen);
                                         return payload;

                                     } catch (Exception e) {
                                         System.err.println("⚠️ WebSocket: Binary frame parsing error: " + e.getMessage());
                                         return null;
                                     }
                                 }

                                 private void sendPong(byte[] frameData, int headerSize, int payloadLen, ChannelHandlerContext ctx) {
                                     try {
                                         byte[] payload = new byte[payloadLen];
                                         System.arraycopy(frameData, headerSize, payload, 0, payloadLen);
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
                                         if (obj != null && messageJSONHandler != null) {
                                             messageJSONHandler.accept(obj);
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
            // System.err.println("⚠️ WebSocket: Connection failed: " + e.getMessage());
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
            frame.writeByte(0x81); // Text frame

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

    public void sendBinary(byte[] data) {
        if (!isConnected()) return;

        try {
            byte[] mask = new byte[4];
            new SecureRandom().nextBytes(mask);

            ByteBuf frame = Unpooled.buffer();
            frame.writeByte(0x82); // Binary frame

            int payloadLen = data.length;
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
                frame.writeByte(data[i] ^ mask[i % 4]);
            }

            channel.writeAndFlush(frame);
        } catch (Exception e) {
            System.err.println("⚠️ WebSocket: Binary send error: " + e.getMessage());
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
