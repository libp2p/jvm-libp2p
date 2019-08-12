package io.libp2p.tools.p2pd;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;

public class DaemonLauncher {

    public static class Daemon {
        public final P2PDHost host;
        private final Process process;

        public Daemon(P2PDHost host, Process process) {
            this.host = host;
            this.process = process;
        }

        public void kill() {
            process.destroyForcibly();
        }
    }

    private final String daemonPath;
    private int commandPort = 11111;

    public DaemonLauncher(String daemonPath) {
        this.daemonPath = daemonPath;
    }

    public Daemon launch(int nodePort, String ... commandLineArgs) {
        ArrayList<String> args = new ArrayList<>();
        int cmdPort = commandPort++;
        args.add(daemonPath);
        args.add("-listen");
        args.add("/ip4/127.0.0.1/tcp/" + cmdPort);
        args.add("-hostAddrs");
        args.add("/ip4/127.0.0.1/tcp/" + nodePort);
        args.addAll(Arrays.asList(commandLineArgs));
        try {
            Process process = new ProcessBuilder(args).inheritIO().start();
            return new Daemon(new P2PDHost(new InetSocketAddress("127.0.0.1", cmdPort)), process);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
