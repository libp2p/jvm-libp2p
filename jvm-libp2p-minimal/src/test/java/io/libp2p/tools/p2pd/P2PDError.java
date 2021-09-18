package io.libp2p.tools.p2pd;

/**
 * Created by Anton Nashatyrev on 14.12.2018.
 */
public class P2PDError extends RuntimeException {
    public P2PDError(String message) {
        super(message);
    }
}
