package io.libp2p.tools.p2pd.libp2pj.exceptions;

/**
 * Created by Anton Nashatyrev on 11.12.2018.
 */
public class MalformedMultiaddressException extends RuntimeException {
    public MalformedMultiaddressException(String message) {
        super(message);
    }
}
