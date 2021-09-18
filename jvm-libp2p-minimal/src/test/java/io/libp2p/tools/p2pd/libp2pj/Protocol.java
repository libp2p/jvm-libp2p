package io.libp2p.tools.p2pd.libp2pj;

/**
 * Created by Anton Nashatyrev on 18.12.2018.
 */
public class Protocol {
    private final String name;

    public Protocol(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return getName();
    }
}
