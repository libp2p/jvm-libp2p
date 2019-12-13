package javax.jmdns.impl.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;

/**
 * This class contains all the byte shifting 
 * 
 * @author Victor Toni
 *
 */
public class ByteWrangler {
    /**
     * Maximum number of bytes a value can consist of.
     */
    public static final int MAX_VALUE_LENGTH = 255;

    /**
     * Maximum number of bytes record data can consist of.
     * It is {@link #MAX_VALUE_LENGTH} + 1 because the first byte contains the number of the following bytes.
     */
    public static final int MAX_DATA_LENGTH = MAX_VALUE_LENGTH + 1;

    /**
     * Representation of no value. A zero length array of bytes.
     */
    public static final byte[] NO_VALUE = new byte[0];

    /**
     * Representation of empty text.
     * The first byte denotes the length of the following character bytes (in this case zero.)
     *
     * FIXME: Should this be exported as a method since it could change externally???
     */
    public final static byte[] EMPTY_TXT = new byte[] { 0 };

    /**
     * Name for charset used to convert Strings to/from wire bytes: {@value #CHARSET_NAME}.
     */
    public final static String CHARSET_NAME = "UTF-8";

    /**
     * Charset used to convert Strings to/from wire bytes: {@value #CHARSET_NAME}.
     */
    private final static Charset CHARSET_UTF_8 = Charset.forName(CHARSET_NAME);

    public static byte[] encodeText(final String text) throws IOException {
        final byte data[] = text.getBytes(CHARSET_UTF_8);
        if (data.length > MAX_VALUE_LENGTH) {
            return EMPTY_TXT;
        }

        final ByteArrayOutputStream out = new ByteArrayOutputStream(MAX_DATA_LENGTH);
        out.write((byte) data.length);
        out.write(data, 0, data.length);

        final byte[] encodedText = out.toByteArray();
        return encodedText;
    }
}
