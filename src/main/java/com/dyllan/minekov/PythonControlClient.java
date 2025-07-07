package com.dyllan.minekov;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.net.URI;
import java.nio.charset.StandardCharsets;
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
                                     // 👇 Manual HTTP 1.1 WebSocket upgrade request
                                     String request =
                                             "GET " + uri.getPath() + " HTTP/1.1\r\n" +
                                             "Host: " + host + "\r\n" +
                                             "Upgrade: websocket\r\n" +
                                             "Connection: Upgrade\r\n" +
                                             "Sec-WebSocket-Key: " + Base64.getEncoder().encodeToString("minekov12345678".getBytes()) + "\r\n" +
                                             "Sec-WebSocket-Version: 13\r\n" +
                                             "\r\n";
                                     ctx.writeAndFlush(Unpooled.copiedBuffer(request, StandardCharsets.US_ASCII));
                                 }

                                 @Override
                                 protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                                     if (!handshakeComplete) {
                                         String response = msg.toString(StandardCharsets.US_ASCII);
                                         if (response.contains("101 Switching Protocols")) {
                                             System.out.println("WebSocket connected to Python backend.");
                                             handshakeComplete = true;
                                         } else {
                                             System.err.println("Handshake failed:\n" + response);
                                             ctx.close();
                                         }
                                         return;
                                     }

                                     // ✅ Raw WebSocket frame (after handshake)
                                     byte opcode = msg.getByte(0);
                                     if ((opcode & 0x0F) == 0x1) { // text frame
                                         int payloadLen = msg.getByte(1) & 0x7F;
                                         String message = msg.slice(2, payloadLen).toString(StandardCharsets.UTF_8);
                                         System.out.println("Received command: " + message);
                                     } else {
                                         System.out.println("Received non-text frame");
                                     }
                                 }

                                 @Override
                                 public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                     cause.printStackTrace();
                                     ctx.close();
                                 }

                                 @Override
                                 public void channelInactive(ChannelHandlerContext ctx) {
                                     System.out.println("WebSocket closed.");
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
        if (isConnected()) {
            byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
            int length = bytes.length;

            // 0x81 = FIN + text frame
            ByteBuf frame = Unpooled.buffer(length + 2);
            frame.writeByte(0x81); // FIN + text opcode
            frame.writeByte(length); // no masking, assuming Python backend is OK with it
            frame.writeBytes(bytes);

            channel.writeAndFlush(frame);
        }
    }
}
