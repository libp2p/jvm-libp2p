// /Copyright 2003-2005 Arthur van Hoff, Rick Blair
// Licensed under Apache License version 2.0
// Original license LGPL

package io.libp2p.discovery.mdns.impl;

import io.libp2p.discovery.mdns.AnswerListener;
import io.libp2p.discovery.mdns.JmDNS;
import io.libp2p.discovery.mdns.ServiceInfo;
import io.libp2p.discovery.mdns.impl.constants.DNSConstants;
import io.libp2p.discovery.mdns.impl.constants.DNSRecordType;
import io.libp2p.discovery.mdns.impl.tasks.Responder;
import io.libp2p.discovery.mdns.impl.tasks.ServiceResolver;
import io.libp2p.discovery.mdns.impl.util.NamedThreadFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Derived from mDNS implementation in Java.
 *
 * @author Arthur van Hoff, Rick Blair, Jeff Sonstein, Werner Randelshofer, Pierre Frisch, Scott Lewis, Kai Kreuzer, Victor Toni
 */
public class JmDNSImpl extends JmDNS {
    private static Logger logger = LogManager.getLogger(JmDNSImpl.class.getName());

    /**
     * This is the multicast group, we are listening to for multicast DNS messages.
     */
    private volatile InetAddress _group;
    /**
     * This is our multicast socket.
     */
    private volatile MulticastSocket _socket;

    private final ConcurrentMap<String, List<AnswerListener>> _answerListeners;
    private final ConcurrentMap<String, ServiceResolver> _serviceResolvers;

    /**
     * This hashtable holds the services that have been registered. Keys are instances of String which hold an all lower-case version of the fully qualified service name. Values are instances of ServiceInfo.
     */
    private final ConcurrentMap<String, ServiceInfo> _services;

    /**
     * Handle on the local host
     */
    private HostInfo _localHost;

    private SocketListener _incomingListener;

    private final ExecutorService _executor = Executors.newSingleThreadExecutor(new NamedThreadFactory("JmDNS"));

    /**
     * The source for random values. This is used to introduce random delays in responses. This reduces the potential for collisions on the network.
     */
    private final static Random _random = new Random();

    /**
     * This lock is used to coordinate processing of incoming and outgoing messages. This is needed, because the Rendezvous Conformance Test does not forgive race conditions.
     */
    private final ReentrantLock _ioLock = new ReentrantLock();

    private final String _name;

    /**
     * Create an instance of JmDNS and bind it to a specific network interface given its IP-address.
     *
     * @param address IP address to bind to.
     * @param name    name of the newly created JmDNS
     * @throws IOException
     */
    public JmDNSImpl(InetAddress address, String name) {
        super();
        logger.debug("JmDNS instance created");

        _answerListeners = new ConcurrentHashMap<>();
        _serviceResolvers = new ConcurrentHashMap<>();

        _services = new ConcurrentHashMap<>(20);

        _localHost = HostInfo.newHostInfo(address, this, name);
        _name = (name != null ? name : _localHost.getName());
    }

    public void start() throws IOException {
        // Bind to multicast socket
        this.openMulticastSocket(this.getLocalHost());
        this.start(this.getServices().values());
    }

    private void start(Collection<? extends ServiceInfo> serviceInfos) {
        if (_incomingListener == null) {
            _incomingListener = new SocketListener(this);
            _incomingListener.start();
        }
        for (ServiceInfo info : serviceInfos) {
            try {
                this.registerService(new ServiceInfoImpl(info));
            } catch (final Exception exception) {
                logger.warn("start() Registration exception ", exception);
            }
        }
    }

    private void openMulticastSocket(HostInfo hostInfo) throws IOException {
        if (_group == null) {
            if (hostInfo.getInetAddress() instanceof Inet6Address) {
                _group = InetAddress.getByName(DNSConstants.MDNS_GROUP_IPV6);
            } else {
                _group = InetAddress.getByName(DNSConstants.MDNS_GROUP);
            }
        }
        if (_socket != null) {
            this.closeMulticastSocket();
        }
        // SocketAddress address = new InetSocketAddress((hostInfo != null ? hostInfo.getInetAddress() : null), DNSConstants.MDNS_PORT);
        // System.out.println("Socket Address: " + address);
        // try {
        // _socket = new MulticastSocket(address);
        // } catch (Exception exception) {
        // logger.warn("openMulticastSocket() Open socket exception Address: " + address + ", ", exception);
        // // The most likely cause is a duplicate address lets open without specifying the address
        // _socket = new MulticastSocket(DNSConstants.MDNS_PORT);
        // }
        _socket = new MulticastSocket(DNSConstants.MDNS_PORT);
        if ((hostInfo != null) && (hostInfo.getInterface() != null)) {
            final SocketAddress multicastAddr = new InetSocketAddress(_group, DNSConstants.MDNS_PORT);
            _socket.setNetworkInterface(hostInfo.getInterface());

            logger.trace("Trying to joinGroup({}, {})", multicastAddr, hostInfo.getInterface());

            // this joinGroup() might be less surprisingly so this is the default
            _socket.joinGroup(multicastAddr, hostInfo.getInterface());
        } else {
            logger.trace("Trying to joinGroup({})", _group);
            _socket.joinGroup(_group);
        }

        _socket.setTimeToLive(255);
    }

    private void closeMulticastSocket() {
        // jP: 20010-01-18. See below. We'll need this monitor...
        // assert (Thread.holdsLock(this));
        logger.debug("closeMulticastSocket()");
        if (_socket != null) {
            // close socket
            try {
                try {
                    _socket.leaveGroup(_group);
                } catch (SocketException exception) {
                    //
                }
                _socket.close();
            } catch (final Exception exception) {
                logger.warn("closeMulticastSocket() Close socket exception ", exception);
            }
            _socket = null;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getName() {
        return _name;
    }

    /**
     * Returns the local host info
     *
     * @return local host info
     */
    public HostInfo getLocalHost() {
        return _localHost;
    }

    void handleServiceAnswers(List<DNSRecord> answers) {
        DNSRecord ptr = answers.get(0);
        if (!DNSRecordType.TYPE_PTR.equals(ptr.getRecordType()))
            return;
        List<AnswerListener> list = _answerListeners.get(ptr.getKey());

        if ((list != null) && (!list.isEmpty())) {
            final List<AnswerListener> listCopy;
            synchronized (list) {
                listCopy = new ArrayList<>(list);
            }
            for (final AnswerListener listener : listCopy) {
                _executor.submit(new Runnable() {
                    @Override
                    public void run() {
                        listener.answersReceived(answers);
                    }
                });
            }
        }
    }

    @Override
    public void addAnswerListener(String type, int queryInterval, AnswerListener listener) {
        final String loType = type.toLowerCase();
        List<AnswerListener> list = _answerListeners.get(loType);
        if (list == null) {
            _answerListeners.putIfAbsent(loType, new LinkedList<>());
            list = _answerListeners.get(loType);
        }
        if (list != null) {
            synchronized (list) {
                if (!list.contains(listener)) {
                    list.add(listener);
                }
            }
        }

        startServiceResolver(loType, queryInterval);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void registerService(ServiceInfo infoAbstract) throws IOException {
        final ServiceInfoImpl info = (ServiceInfoImpl) infoAbstract;

        info.setServer(_localHost.getName());

        _services.putIfAbsent(info.getKey(), info);

        logger.debug("registerService() JmDNS registered service as {}", info);
    }

    /**
     * Handle an incoming response. Cache answers, and pass them on to the appropriate questions.
     *
     * @throws IOException
     */
    void handleResponse(DNSIncoming msg) throws IOException {
        List<DNSRecord> allAnswers = msg.getAllAnswers();
        allAnswers = aRecordsLast(allAnswers);

        handleServiceAnswers(allAnswers);
    }

    /**
     * In case the a record is received before the srv record the ip address would not be set.
     * <p>
     * Multicast Domain Name System (response)
     * Transaction ID: 0x0000
     * Flags: 0x8400 Standard query response, No error
     * Questions: 0
     * Answer RRs: 2
     * Authority RRs: 0
     * Additional RRs: 8
     * Answers
     * _ibisip_http._tcp.local: type PTR, class IN, DeviceManagementService._ibisip_http._tcp.local
     * _ibisip_http._tcp.local: type PTR, class IN, PassengerCountingService._ibisip_http._tcp.local
     * Additional records
     * DeviceManagementService._ibisip_http._tcp.local: type TXT, class IN, cache flush
     * PassengerCountingService._ibisip_http._tcp.local: type TXT, class IN, cache flush
     * DIST500_7-F07_OC030_05_03941.local: type A, class IN, cache flush, addr 192.168.88.236
     * DeviceManagementService._ibisip_http._tcp.local: type SRV, class IN, cache flush, priority 0, weight 0, port 5000, target DIST500_7-F07_OC030_05_03941.local
     * PassengerCountingService._ibisip_http._tcp.local: type SRV, class IN, cache flush, priority 0, weight 0, port 5001, target DIST500_7-F07_OC030_05_03941.local
     * DeviceManagementService._ibisip_http._tcp.local: type NSEC, class IN, cache flush, next domain name DeviceManagementService._ibisip_http._tcp.local
     * PassengerCountingService._ibisip_http._tcp.local: type NSEC, class IN, cache flush, next domain name PassengerCountingService._ibisip_http._tcp.local
     * DIST500_7-F07_OC030_05_03941.local: type NSEC, class IN, cache flush, next domain name DIST500_7-F07_OC030_05_03941.local
     */
    private List<DNSRecord> aRecordsLast(List<DNSRecord> allAnswers) {
        ArrayList<DNSRecord> ret = new ArrayList<DNSRecord>(allAnswers.size());
        ArrayList<DNSRecord> arecords = new ArrayList<DNSRecord>();

        for (DNSRecord answer : allAnswers) {
            DNSRecordType type = answer.getRecordType();
            if (type.equals(DNSRecordType.TYPE_A) || type.equals(DNSRecordType.TYPE_AAAA)) {
                arecords.add(answer);
            } else if (type.equals(DNSRecordType.TYPE_PTR)) {
                ret.add(0, answer);
            } else {
                ret.add(answer);
            }
        }
        ret.addAll(arecords);
        return ret;
    }


    /**
     * Handle an incoming query. See if we can answer any part of it given our service infos.
     *
     * @param in
     * @param addr
     * @param port
     * @throws IOException
     */
    void handleQuery(DNSIncoming in, InetAddress addr, int port) throws IOException {
        logger.debug("{} handle query: {}", this.getName(), in);
        this.ioLock();
        try {
            DNSIncoming plannedAnswer = in.clone();
            this.startResponder(plannedAnswer, addr, port);
        } finally {
            this.ioUnlock();
        }
    }

    /**
     * Send an outgoing multicast DNS message.
     *
     * @param out
     * @throws IOException
     */
    public void send(DNSOutgoing out) throws IOException {
        if (!out.isEmpty()) {
            final InetAddress addr;
            final int port;

            if (out.getDestination() != null) {
                addr = out.getDestination().getAddress();
                port = out.getDestination().getPort();
            } else {
                addr = _group;
                port = DNSConstants.MDNS_PORT;
            }

            byte[] message = out.data();
            final DatagramPacket packet = new DatagramPacket(message, message.length, addr, port);

            if (logger.isTraceEnabled()) {
                try {
                    final DNSIncoming msg = new DNSIncoming(packet);
                    if (logger.isTraceEnabled()) {
                        logger.trace("send({}) JmDNS out:{}", this.getName(), msg.print(true));
                    }
                } catch (final IOException e) {
                    logger.debug(getClass().toString(), ".send(" + this.getName() + ") - JmDNS can not parse what it sends!!!", e);
                }
            }
            final MulticastSocket ms = _socket;
            if (ms != null && !ms.isClosed()) {
                ms.send(packet);
            }
        }
    }

    private void startServiceResolver(String type, int queryInterval) {
        if (_serviceResolvers.containsKey(type))
            return;

        ServiceResolver resolver = new ServiceResolver(this, type, queryInterval);
        if (_serviceResolvers.putIfAbsent(type, resolver) == null)
            resolver.start();
    }

    private void startResponder(DNSIncoming in, InetAddress addr, int port) {
        new Responder(this, in, addr, port).start();
    }

    public void stop() {
        logger.debug("Stopping JmDNS: {}", this);

        List<Future<Void>> shutdowns = new ArrayList<>();

        shutdowns.add(_incomingListener.stop());
        _incomingListener = null;

        for (ServiceResolver resolver : _serviceResolvers.values())
            shutdowns.add(resolver.stop());

        // close socket
        this.closeMulticastSocket();

        logger.debug("JmDNS waiting for service stop...");

        for (Future<Void> shutdown : shutdowns) {
            try {
                shutdown.get(10, TimeUnit.SECONDS);
            } catch (CancellationException e) {
                logger.trace("Task was already cancelled", e);
            } catch (InterruptedException e) {
                logger.trace("Stopping was interrupted", e);
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException e) {
                logger.debug("Exception when stopping JmDNS: ", e);
                throw new RuntimeException(e);
            }
        }

        _executor.shutdown();

        logger.debug("JmDNS stopped.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder(2048);
        sb.append("\n");
        sb.append("\t---- Local Host -----");
        sb.append("\n\t");
        sb.append(_localHost);
        sb.append("\n\t---- Services -----");
        for (final Map.Entry<String, ServiceInfo> entry : _services.entrySet()) {
            sb.append("\n\t\tService: ");
            sb.append(entry.getKey());
            sb.append(": ");
            sb.append(entry.getValue());
        }
        sb.append("\n");
        sb.append("\t---- Answer Listeners ----");
        for (final Map.Entry<String, List<AnswerListener>> entry : _answerListeners.entrySet()) {
            sb.append("\n\t\tAnswer Listener: ");
            sb.append(entry.getKey());
            sb.append(": ");
            sb.append(entry.getValue());
        }
        return sb.toString();
    }

    public Map<String, ServiceInfo> getServices() {
        return _services;
    }

    public static Random getRandom() {
        return _random;
    }

    private void ioLock() {
        _ioLock.lock();
    }

    private void ioUnlock() {
        _ioLock.unlock();
    }

    public MulticastSocket getSocket() {
        return _socket;
    }

    public InetAddress getGroup() {
        return _group;
    }
}
