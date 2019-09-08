package io.libp2p.tools.p2pd.libp2pj.util;

import java.util.Arrays;
import java.util.List;

/**
 * Created by Anton Nashatyrev on 17.12.2018.
 */
public class Util {

    public static byte[] concat(List<byte[]> arrays) {
        byte[] ret = new byte[arrays.stream().mapToInt(arr -> arr.length).sum()];
        int off = 0;
        for (byte[] bb : arrays) {
            System.arraycopy(bb, 0, ret, off, bb.length);
            off += bb.length;
        }
        return ret;
    }

    /**
     * https://developers.google.com/protocol-buffers/docs/encoding
     */
    public static byte[] encodeUVariant(long n) {
        int size = 0;
        byte[] ret = new byte[10];
        while(n > 0) {
            if (size > 0) ret[size - 1] |= 0b10000000;
            ret[size++] = (byte) (n & 0b01111111);
            n >>>= 7;
        }
        return Arrays.copyOfRange(ret, 0, size);
    }
}
