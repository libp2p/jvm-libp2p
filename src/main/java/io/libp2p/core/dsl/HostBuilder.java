package io.libp2p.core.dsl;

import io.libp2p.core.Host;
import io.libp2p.core.crypto.PrivKey;
import io.libp2p.core.multistream.ProtocolBinding;
import io.libp2p.core.mux.*;
import io.libp2p.core.security.SecureChannel;
import io.libp2p.core.transport.Transport;
import io.libp2p.transport.ConnectionUpgrader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.*;

public class HostBuilder {
    public HostBuilder() { this(DefaultMode.Standard); }
    public HostBuilder(DefaultMode defaultMode) {
        defaultMode_ = defaultMode;
    }

    public enum DefaultMode {
        None,
        Standard;

        private Builder.Defaults asBuilderDefault() {
            if (this.equals(None)) {
                return Builder.Defaults.None;
            }
            return Builder.Defaults.Standard;
        }
    };

    @SafeVarargs
    public final HostBuilder transport(
            Function<ConnectionUpgrader, Transport>... transports) {
        transports_.addAll(Arrays.asList(transports));
        return this;
    }

    @SafeVarargs
    public final HostBuilder secureChannel(
            BiFunction<PrivKey, List<String>, SecureChannel>... secureChannels) {
        secureChannels_.addAll(Arrays.asList(secureChannels));
        return this;
    }

    @SafeVarargs
    public final HostBuilder muxer(
            Supplier<StreamMuxerProtocol>... muxers) {
        muxers_.addAll(Arrays.asList(muxers));
        return this;
    }

    public final HostBuilder protocol(
            ProtocolBinding<?>... protocols) {
        protocols_.addAll(Arrays.asList(protocols));
        return this;
    }

    public final HostBuilder listen(
            String... addresses) {
        listenAddresses_.addAll(Arrays.asList(addresses));
        return this;
    }


    public Host build() {
        return BuilderJKt.hostJ(
            defaultMode_.asBuilderDefault(),
            b -> {
                b.getIdentity().random();

                transports_.forEach(t ->
                    b.getTransports().add(t::apply)
                );
                secureChannels_.forEach(sc ->
                    b.getSecureChannels().add((k, m) -> sc.apply(k, (List<String>)m))
                );
                muxers_.forEach(m ->
                    b.getMuxers().add(m.get())
                );
                b.getProtocols().addAll(protocols_);
                listenAddresses_.forEach(a ->
                    b.getNetwork().listen(a)
                );
            }
        );
    } // build

    private DefaultMode defaultMode_;
    private List<Function<ConnectionUpgrader, Transport>> transports_ = new ArrayList<>();
    private List<BiFunction<PrivKey, List<String>, SecureChannel>> secureChannels_ = new ArrayList<>();
    private List<Supplier<StreamMuxerProtocol>> muxers_ = new ArrayList<>();
    private List<ProtocolBinding<?>> protocols_ = new ArrayList<>();
    private List<String> listenAddresses_ = new ArrayList<>();
}
