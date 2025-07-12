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
    private final BinaryActionDecoder binaryDecoder = new BinaryActionDecoder();

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
                                             send("{\"type\": \"hello\", \"msg\": \"Java online\"}");
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
                                         // For 64-bit length, only use lower 32 bits and check for overflow
                                         long longLength = frameBuffer.getLong(frameBuffer.readerIndex() + 2);
                                         if (longLength > Integer.MAX_VALUE || longLength < 0) {
                                             System.err.println("⚠️ Frame too large: " + longLength + " bytes");
                                             frameBuffer.skipBytes(frameBuffer.readableBytes()); // Skip entire buffer
                                             return true;
                                         }
                                         actualPayloadLen = (int) longLength;
                                         headerSize = 10;
                                     }
                                     
                                     // Validate payload length
                                     if (actualPayloadLen < 0) {
                                         System.err.println("⚠️ Invalid negative payload length: " + actualPayloadLen);
                                         frameBuffer.skipBytes(Math.min(2, frameBuffer.readableBytes())); // Skip minimal bytes
                                         return true;
                                     }
                                     
                                     int totalFrameSize = headerSize + actualPayloadLen;
                                     
                                     // Additional validation
                                     if (totalFrameSize < 0 || totalFrameSize > frameBuffer.capacity()) {
                                         System.err.println("⚠️ Invalid frame size: " + totalFrameSize + " (header: " + headerSize + ", payload: " + actualPayloadLen + ")");
                                         frameBuffer.skipBytes(Math.min(headerSize, frameBuffer.readableBytes()));
                                         return true;
                                     }
                                     
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
                                         processTextFrame(frameData, headerSize, actualPayloadLen);
                                         return true;
                                     }

                                     // Handle binary frames
                                     if (opcode == 0x2) {
                                         processBinaryFrame(frameData, headerSize, actualPayloadLen);
                                         return true;
                                     }
                                     
                                     // Unknown frame type, skip it
                                     return true;
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
                                 
                                 private void processTextFrame(byte[] frameData, int headerSize, int payloadLen) {
                                     try {
                                         // Extract payload directly using known header size and payload length
                                         if (payloadLen < 0 || headerSize + payloadLen > frameData.length) {
                                             System.err.println("⚠️ WebSocket: Invalid text frame - payload: " + payloadLen + 
                                                              ", header: " + headerSize + ", total: " + frameData.length);
                                             return;
                                         }

                                         byte[] payload = new byte[payloadLen];
                                         System.arraycopy(frameData, headerSize, payload, 0, payloadLen);
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
                                                     // Removed logging to reduce overhead
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
                                             // Silently handle JSON parsing errors
                                         }
                                     } catch (Exception e) {
                                         System.err.println("⚠️ WebSocket: Text frame processing error: " + e.getMessage());
                                     }
                                 }

                                 private void processBinaryFrame(byte[] frameData, int headerSize, int payloadLen) {
                                     try {
                                         // Extract binary payload
                                         if (payloadLen < 0 || headerSize + payloadLen > frameData.length) {
                                             System.err.println("⚠️ WebSocket: Invalid binary frame - payload: " + payloadLen + 
                                                              ", header: " + headerSize + ", total: " + frameData.length);
                                             return;
                                         }

                                         byte[] payload = new byte[payloadLen];
                                         System.arraycopy(frameData, headerSize, payload, 0, payloadLen);
                                         
                                         // Use the BinaryActionDecoder to process the binary data
                                         binaryDecoder.processBinaryMessage(payload);
                                         
                                     } catch (Exception e) {
                                         System.err.println("⚠️ WebSocket: Binary frame processing error: " + e.getMessage());
                                     }
                                 }

                                 @Override
                                 public void channelInactive(ChannelHandlerContext ctx) {
                                     // Clean up frame buffer on disconnect
                                     if (frameBuffer != null) {
                                         frameBuffer.release();
                                         frameBuffer = null;
                                     }
                                 }
                                 
                                 @Override
                                 public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                     // Clean up frame buffer on error
                                     if (frameBuffer != null) {
                                         frameBuffer.release();
                                         frameBuffer = null;
                                     }
                                     // Only log non-connection errors to avoid spam
                                     if (!(cause instanceof java.net.ConnectException) && 
                                         !(cause.getCause() instanceof java.net.ConnectException)) {
                                         System.err.println("❗ WebSocket error:");
                                         cause.printStackTrace();
                                     }
                                     ctx.close();
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
                            // System.out.println("🕹️ Moving operator " + id.substring(0, 8) + " → angle=" + angle + "°");
                        }
                    }
                    case "fire" -> {
                        // 🎯 Execute firing directly on main thread
                        op.shootForward();
                        // System.out.println("🔫 Operator " + id.substring(0, 8) + " fired!");
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
