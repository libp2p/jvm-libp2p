package io.libp2p.tools.p2pd.libp2pj;

import io.libp2p.core.multiformats.Multiaddr;

import java.util.List;

/**
 * Created by Anton Nashatyrev on 20.12.2018.
 */
public class PeerInfo {
    private final Peer id;
    private final List<Multiaddr> addresses;

    public PeerInfo(Peer id, List<Multiaddr> addresses) {
        this.id = id;
        this.addresses = addresses;
    }

    public Peer getId() {
        return id;
    }

    public List<Multiaddr> getAddresses() {
        return addresses;
    }

    @Override
    public String toString() {
        return "PeerInfo{" +
                "id=" + id +
                ", adresses=" + addresses +
                '}';
    }
}
