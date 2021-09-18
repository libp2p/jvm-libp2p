package io.libp2p.tools.p2pd.libp2pj.exceptions;

/**
 * Created by Anton Nashatyrev on 11.12.2018.
 */
public class UnsupportedTransportException extends RuntimeException {
    public UnsupportedTransportException(String message) {
        super(message);
    }
}
