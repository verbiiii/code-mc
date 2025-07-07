package com.dyllan.minekov;

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

public class PythonControlClient {

    private final URI uri;
    private Channel channel;
    private final EventLoopGroup group = new NioEventLoopGroup();

    public PythonControlClient(URI uri) {
        this.uri = uri;
    }

    public void connect() {
        try {
            String host = uri.getHost();
            int port = uri.getPort() == -1 ? 80 : uri.getPort();
            String path = uri.getPath().isEmpty() ? "/" : uri.getPath();

            // Generate valid 16-byte Sec-WebSocket-Key
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
                                     if (!handshakeComplete) {
                                         String response = msg.toString(StandardCharsets.US_ASCII);
                                         if (response.contains("101 Switching Protocols")) {
                                             System.out.println("✅ WebSocket connected to Python backend.");
                                             handshakeComplete = true;

                                             // 🔥 test message
                                             send("{\"type\": \"hello\", \"msg\": \"Java online\"}");
                                         } else {
                                             System.err.println("❌ Handshake failed:\n" + response);
                                             ctx.close();
                                         }
                                         return;
                                     }

                                     // Read text frames only (0x1)
                                     byte opcode = msg.getByte(0);
                                     if ((opcode & 0x0F) == 0x1) {
                                         int payloadLen = msg.getByte(1) & 0x7F;
                                         String message = msg.slice(2, payloadLen).toString(StandardCharsets.UTF_8);
                                         System.out.println("📩 Received command: " + message);
                                     } else {
                                         System.out.println("📎 Received non-text frame");
                                     }
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
        return channel != null && channel.isOpen();
    }

    public void send(String message) {
        if (!isConnected()) return;

        byte[] payload = message.getBytes(StandardCharsets.UTF_8);
        byte[] mask = new byte[4];
        new SecureRandom().nextBytes(mask);

        ByteBuf frame = Unpooled.buffer();
        frame.writeByte(0x81); // FIN + text frame

        int payloadLen = payload.length;
        if (payloadLen <= 125) {
            frame.writeByte(0x80 | payloadLen); // 0x80 = masked
        } else if (payloadLen <= 0xFFFF) {
            frame.writeByte(0x80 | 126);
            frame.writeShort(payloadLen);
        } else {
            frame.writeByte(0x80 | 127);
            frame.writeLong(payloadLen);
        }

        frame.writeBytes(mask);

        for (int i = 0; i < payloadLen; i++) {
            frame.writeByte(payload[i] ^ mask[i % 4]); // apply mask
        }

        channel.writeAndFlush(frame);
    }
}
