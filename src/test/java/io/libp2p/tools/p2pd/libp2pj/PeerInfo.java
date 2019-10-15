package io.libp2p.tools.p2pd.libp2pj;

import io.libp2p.core.multiformats.Multiaddr;

import java.util.List;

/**
 * Created by Anton Nashatyrev on 20.12.2018.
 */
public class PeerInfo {
    private final Peer id;
    private final List<Multiaddr> adresses;

    public PeerInfo(Peer id, List<Multiaddr> adresses) {
        this.id = id;
        this.adresses = adresses;
    }

    public Peer getId() {
        return id;
    }

    public List<Multiaddr> getAdresses() {
        return adresses;
    }

    @Override
    public String toString() {
        return "PeerInfo{" +
                "id=" + id +
                ", adresses=" + adresses +
                '}';
    }
}
