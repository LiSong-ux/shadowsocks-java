package org.wiyi.ss;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wiyi.ss.core.SSConfig;
import org.wiyi.ss.core.cipher.SSCipher;
import org.wiyi.ss.core.cipher.SSCipherFactories;
import org.wiyi.ss.net.codec.SSAddressingCodec;
import org.wiyi.ss.net.codec.SSCipherCodec;
import org.wiyi.ss.net.codec.SSTCPAddressDecoder;
import org.wiyi.ss.net.codec.SSUDPSessionCodec;
import org.wiyi.ss.net.tcp.SSServerTCPRelayHandler;
import org.wiyi.ss.net.udp.SSServerUDPRelayHandler;

public class SSServer implements Server {
    private static final Logger logger = LoggerFactory.getLogger(SSServer.class);

    private final SSConfig config;
    private boolean isRunning;
    private Server tcpServer;
    private Server udpServer;

    public SSServer(SSConfig config) {
        this.config = config;
    }

    public synchronized void start() {
        if (isRunning) {
            throw new IllegalStateException("ss-local is running.");
        }

        tcpServer = new SSServerTCPServer();
        tcpServer.open();
        udpServer = new SSServerUDPServer();
        udpServer.open();

        isRunning = true;
    }

    public synchronized void stop() {
        if (isRunning) {
            return;
        }

        if (tcpServer != null && !tcpServer.isClosed()) {
            tcpServer.close();
        }

        if (udpServer != null && udpServer.isClosed()) {
            udpServer.close();
        }

        isRunning = false;
    }

    @Override
    public void open() {
        start();
    }

    @Override
    public void close() {
        stop();
    }

    @Override
    public boolean isClosed() {
        return isRunning;
    }

    /**
     * 该内部类涉及到netty的使用【重点】
     */
    private class SSServerTCPServer implements Server {
        // 创建两个线程组，一个用于监听请求，一个用于处理逻辑
        private EventLoopGroup acceptor = new NioEventLoopGroup();
        private EventLoopGroup worker = new NioEventLoopGroup();

        @Override
        public void open() {
            // ServerBootstrap是一个引导类，其对象的作用是引导服务器的启动工作
            ServerBootstrap bootstrap = new ServerBootstrap();
            // .group是配置上面两个线程组的角色，也就是谁去监听谁去处理读写。上面只是创建了两个线程组，并没有实际使用
            // .channel是配置服务端的IO模型，上面代码配置的是NIO模型。也可以配置为BIO，如OioServerSocketChannel.class
            bootstrap.group(acceptor, worker).channel(NioServerSocketChannel.class)
                    // .option是给服务端的channel设置属性
                    // 设置接收缓冲区大小为32k
                    .option(ChannelOption.SO_RCVBUF, 32 * 1024)
                    // 设置请求队列的容量为128
                    .option(ChannelOption.SO_BACKLOG, 128)
                    // 设置数据即时传输，不合并数据包
                    .option(ChannelOption.TCP_NODELAY, true)
                    // 设置连接的心跳检测
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    // 配置每个连接的业务逻辑
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            SSCipher cipher = SSCipherFactories.newInstance(config.getPassword(), config.getMethod());
                            ChannelPipeline pipe = ch.pipeline();
                            // 可能对应SS协议的三个阶段
                            pipe.addLast(new SSCipherCodec(cipher));
                            pipe.addLast(new SSTCPAddressDecoder());
                            pipe.addLast(new SSServerTCPRelayHandler(config));
                        }
                    });
            try {
                logger.info("ss-server listen on tcp {}:{}", config.getServer(), config.getServerPort());
                bootstrap.bind(config.getServer(), config.getServerPort()).sync();
            } catch (InterruptedException e) {
                logger.error(e.getMessage(), e);
            }
        }

        @Override
        public void close() {
            if (!acceptor.isTerminated()) {
                acceptor.shutdownGracefully();
            }
            acceptor = null;

            if (!worker.isTerminated()) {
                worker.shutdownGracefully();
            }
            worker = null;
        }

        @Override
        public boolean isClosed() {
            return acceptor.isTerminated() && worker.isTerminated();
        }
    }

    private class SSServerUDPServer implements Server {
        private EventLoopGroup acceptor = new NioEventLoopGroup();

        @Override
        public void open() {
            Bootstrap boot = new Bootstrap();
            boot.group(acceptor).channel(NioDatagramChannel.class)
                    .option(ChannelOption.SO_BROADCAST, false)
                    .option(ChannelOption.SO_RCVBUF, 32 * 1024)
                    .option(ChannelOption.SO_SNDBUF, 32 * 1024)
                    .handler(new ChannelInitializer<NioDatagramChannel>() {
                        @Override
                        protected void initChannel(NioDatagramChannel ctx) throws Exception {
                            SSCipher cipher = SSCipherFactories.newInstance(config.getPassword(), config.getMethod());
                            ChannelPipeline pipe = ctx.pipeline();
                            pipe.addLast(new SSUDPSessionCodec());
                            pipe.addLast(new SSCipherCodec(cipher, false));
                            pipe.addLast(new SSAddressingCodec());
                            pipe.addLast(new SSServerUDPRelayHandler());
                        }
                    });
            try {
                logger.info("ss-server listen on udp {}:{}", config.getServer(), config.getServerPort());
                boot.bind(config.getServer(), config.getServerPort()).sync();
            } catch (InterruptedException e) {
                logger.error(e.getMessage(), e);
                close();
            }
        }

        @Override
        public void close() {
            if (!acceptor.isTerminated()) {
                acceptor.shutdownGracefully();
            }
        }

        @Override
        public boolean isClosed() {
            return acceptor.isTerminated();
        }
    }
}
