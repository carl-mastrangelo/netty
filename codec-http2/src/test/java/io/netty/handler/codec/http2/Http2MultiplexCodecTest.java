/*
 * Copyright 2019 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.netty.handler.codec.http2;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;

public class Http2MultiplexCodecTest extends Http2MultiplexTest<Http2FrameCodec> {

    @Override
    protected Http2FrameCodec newCodec(TestChannelInitializer childChannelInitializer, Http2FrameWriter frameWriter) {
        return new Http2MultiplexCodecBuilder(true, childChannelInitializer).frameWriter(frameWriter).build();
    }

    @Override
    protected ChannelHandler newMultiplexer(TestChannelInitializer childChannelInitializer) {
        return null;
    }

    @Override
    protected boolean useUserEventForResetFrame() {
        return false;
    }

    @Override
    protected boolean ignoreWindowUpdateFrames() {
        return false;
    }

    @Test
    public void flowControl() throws Exception {

        @ChannelHandler.Sharable
        final class LargeWriter extends ChannelDuplexHandler {

            static final int CHUNK_SIZE = 1024*1024;
            static final int CHUNKS = 1024 *1024;
            static final int BATCH_SIZE = 128;

            int writtenChunks;

            @Override
            public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
                writeChunks(ctx);
            }

            private void writeChunks(ChannelHandlerContext ctx) {
                ByteBuf buf = ctx.alloc().buffer(CHUNK_SIZE).writerIndex(CHUNK_SIZE);
                try {
                    while (true) {
                        if (writtenChunks == CHUNKS) {
                            ctx.flush();
                            ctx.close();
                            return;
                        }
                        for (int i = 0; i < BATCH_SIZE && writtenChunks < CHUNKS; i++) {
                            ctx.write(new DefaultHttp2DataFrame(buf.retainedSlice()));
                            writtenChunks++;
                        }
                        if (!ctx.channel().isWritable()) {
                            ctx.flush();
                            return;
                        }
                    }
                } finally {
                    buf.release();
                }
            }

            @Override
            public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
                ctx.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()));
                writeChunks(ctx);
            }
        }

        Http2FrameCodec codec = new Http2MultiplexCodecBuilder(true, new LargeWriter())
                .initialSettings(new Http2Settings())
                .build();
        SocketAddress addr = new InetSocketAddress(0);
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        Channel server = new ServerBootstrap().group(group).childHandler(codec).channel(NioServerSocketChannel.class)
                .bind(addr).sync().channel();

        final class LargeReader extends ChannelDuplexHandler {

            int totalDataRead;

            @Override
            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                super.channelActive(ctx);
                DefaultHttp2Connection conn = new DefaultHttp2Connection(false);
                DefaultHttp2ConnectionEncoder encoder =
                        new DefaultHttp2ConnectionEncoder(conn, new DefaultHttp2FrameWriter());
                DefaultHttp2ConnectionDecoder decoder =
                        new DefaultHttp2ConnectionDecoder(conn, encoder, new DefaultHttp2FrameReader());
                decoder.frameListener(new Http2EventAdapter() {
                    @Override
                    public int onDataRead(
                            ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream) {
                        int ret = data.readableBytes() + padding;
                        totalDataRead += data.readableBytes();
                        return ret;
                    }
                });
                Http2ConnectionHandler http2Handler = new Http2ConnectionHandler(
                        decoder,
                        encoder,
                        new Http2Settings());

                ctx.pipeline().addBefore(ctx.name(), null, http2Handler);
                encoder.writeHeaders(ctx, 3, new DefaultHttp2Headers(), 0, true, ctx.newPromise());
                ctx.flush();
            }
        }
        LargeReader reader = new LargeReader();

        Channel client = new Bootstrap().group(group).channel(NioSocketChannel.class).handler(reader).connect(server.localAddress()).sync().channel();

        client.closeFuture().await(500, TimeUnit.SECONDS);
        server.close().sync();
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
    }
}
