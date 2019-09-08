package io.libp2p.tools.p2pd.libp2pj;

/**
 * Created by Anton Nashatyrev on 18.12.2018.
 */
public class Peer {
    private final byte[] id;

    public Peer(byte[] id) {
        this.id = id;
    }

    public byte[] getIdBytes() {
        return id;
    }

//    public String getIdBase58() {
//        return Base58.encode(getIdBytes());
//    }
//
//    public String getIdHexString() {
//        return Hex.encodeHexString(getIdBytes());
//    }

//    @Override
//    public String toString() {
//        return "Peer{" + "id=" + getIdBase58() + "}";
//    }
}
