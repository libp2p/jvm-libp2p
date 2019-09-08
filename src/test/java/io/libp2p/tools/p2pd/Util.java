package io.libp2p.tools.p2pd;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Created by Anton Nashatyrev on 18.12.2018.
 */
public class Util {
    public static <V> Future<V> futureFromJavaToNetty(CompletableFuture<V> javaFut) {
        Promise<V> ret = ImmediateEventExecutor.INSTANCE.newPromise();
        javaFut.handle((v, t) -> {
            if (t != null) ret.setFailure(t);
            else ret.setSuccess(v);
            return null;
        });
        return ret;
    }

    public static CompletableFuture<Channel> channelFutureToJava(ChannelFuture channelFuture) {
        CompletableFuture<Channel> ret = new CompletableFuture<>();
        channelFuture.addListener((ChannelFutureListener) future -> {
            try {
                future.get();
                ret.complete(future.channel());
            } catch (Exception e) {
                ret.completeExceptionally(e);
            }
        });
        return ret;
    }

    public static <V> CompletableFuture<V> futureFromNettyToJava(Future<V> nettyFut) {
        CompletableFuture<V> ret = new CompletableFuture<>();
        addListener(nettyFut, ret::complete, ret::completeExceptionally);
        return ret;
    }

    private static <V> void addListener(Future<V> future, Consumer<V> success, Consumer<Throwable> error) {
        if (future == null) return;

        future.addListener((GenericFutureListener<Future<V>>) f -> {
            try {
                V v = f.get();
                if (success != null) success.accept(v);
            } catch (Throwable e) {
                if (error != null) error.accept(e);
            }
        });
    }

    private static <V> Promise<V> newPromise() {
        return ImmediateEventExecutor.INSTANCE.newPromise();
    }

    public static byte[] byteBufToArray(ByteBuf bb) {
        byte[] ret = new byte[bb.readableBytes()];
        bb.readBytes(ret);
        return ret;
    }

    public static byte[] byteBufferToArray(ByteBuffer bb) {
        byte[] ret = new byte[bb.remaining()];
        bb.get(ret);
        return ret;
    }
}
