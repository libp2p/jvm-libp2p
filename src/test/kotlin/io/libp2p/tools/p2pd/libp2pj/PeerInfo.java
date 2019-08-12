package io.libp2p.tools.p2pd.libp2pj;

import io.ipfs.multiaddr.MultiAddress;

import java.util.List;

/**
 * Created by Anton Nashatyrev on 20.12.2018.
 */
public class PeerInfo {
    private final Peer id;
    private final List<MultiAddress> adresses;

    public PeerInfo(Peer id, List<MultiAddress> adresses) {
        this.id = id;
        this.adresses = adresses;
    }

    public Peer getId() {
        return id;
    }

    public List<MultiAddress> getAdresses() {
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
