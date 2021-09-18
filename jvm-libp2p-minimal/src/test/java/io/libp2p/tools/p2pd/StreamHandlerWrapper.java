package io.libp2p.tools.p2pd;

import io.libp2p.tools.p2pd.libp2pj.Stream;
import io.libp2p.tools.p2pd.libp2pj.StreamHandler;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

/**
 * Created by Anton Nashatyrev on 18.12.2018.
 */
public class StreamHandlerWrapper<TEndpoint> implements StreamHandler<TEndpoint> {
    private final StreamHandler<TEndpoint> delegate;
    private Consumer<Stream<TEndpoint>> onCreateListener;
    private Runnable onCloseListener;

    public StreamHandlerWrapper(StreamHandler<TEndpoint> delegate) {
        this.delegate = delegate;
    }

    public StreamHandlerWrapper<TEndpoint> onCreate(Consumer<Stream<TEndpoint>> listener) {
        this.onCreateListener = listener;
        return this;
    }

    public StreamHandlerWrapper<TEndpoint> onClose(Runnable listener) {
        this.onCloseListener = listener;
        return this;
    }

    @Override
    public void onCreate(Stream<TEndpoint> stream) {
        delegate.onCreate(stream);
        if (onCreateListener != null) {
            onCreateListener.accept(stream);
        }
    }

    @Override
    public void onRead(ByteBuffer data) {
        delegate.onRead(data);
    }

    @Override
    public void onClose() {
        delegate.onClose();
        if (onCloseListener != null) {
            onCloseListener.run();
        }
    }

    @Override
    public void onError(Throwable error) {
        delegate.onError(error);
    }
}
