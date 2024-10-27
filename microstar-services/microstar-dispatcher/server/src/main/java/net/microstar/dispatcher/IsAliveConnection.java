package net.microstar.dispatcher;

import lombok.extern.slf4j.Slf4j;
import net.microstar.common.util.ThreadBuilder;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.base.Throwables.getRootCause;
import static net.microstar.common.util.ExceptionUtils.noThrow;

/**
 * Single responsibility of this connection is to check if the other side is still alive.<p>
 *
 * A connection is set up and a byte is sent every maxSilentDuration/2. The connection is
 * running in client mode when an address is given (which it connects to) and server mode
 * when no address is given (it waits max maxInitDuration for a connection being made).
 */
@Slf4j
public class IsAliveConnection {
    private static final byte IS_ALIVE_BYTE = 'A';
    private final Object sync = new Object();
    private final String name;
    private final Duration maxInitDuration;
    private final Duration maxSilenceDuration;
    private final Optional<InetSocketAddress> targetAddress;
    private final List<Runnable> toCallWhenConnectionIsLost = new CopyOnWriteArrayList<>();
    private final AtomicBoolean isInitializing = new AtomicBoolean(false);
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean stop = new AtomicBoolean(false);
    @Nullable private ServerSocket serverSocket; // only set when initiating (server mode)
    @Nullable private Socket clientSocket; // only set when not initiating (client mode)

    public IsAliveConnection(Duration maxInitDuration, Duration maxSilenceDuration, String name) {
        this.name = name;
        this.maxInitDuration = maxInitDuration;
        this.maxSilenceDuration = maxSilenceDuration;
        this.targetAddress = Optional.empty();
    }
    public IsAliveConnection(Duration maxInitDuration, Duration maxSilenceDuration, InetSocketAddress targetAddress, String name) {
        this.name = name;
        this.maxInitDuration = maxInitDuration;
        this.maxSilenceDuration = maxSilenceDuration;
        this.targetAddress = Optional.of(targetAddress);
    }

    public InetSocketAddress getAddress() {
        if(serverSocket == null) throw new IllegalStateException("No ServerSocket to get address of");
        return new InetSocketAddress(serverSocket.getInetAddress(), serverSocket.getLocalPort());
    }

    public IsAliveConnection whenConnectionIsLost(Runnable toCall) {
        toCallWhenConnectionIsLost.add(toCall);
        return this;
    }
    public boolean isConnected() { return clientSocket != null; }
    public boolean isInitializing() { return isInitializing.get(); }
    public boolean isRunning() { return isRunning.get(); }

    public IsAliveConnection start() {
        if(isRunning()) return this;
        if(!isInitializing.compareAndSet(false, true)) return this;
        stop.set(false);

        init();
        new ThreadBuilder()
            .name(name)
            .isDaemon(true)
            .priority(Thread.MAX_PRIORITY)
            .run(this::run);
        return this;
    }
    public void stop() {
        stop.set(true);
        if(serverSocket != null) {
            try { serverSocket.close(); } catch (final IOException ignored) {/*don't care*/}
            serverSocket = null;
        }
        synchronized (sync) {
            sync.notifyAll();
        }
    }

    private void connectionIsLost() {
        toCallWhenConnectionIsLost.forEach(handler -> {
            try {
                handler.run();
            } catch(final Exception e) {
                //noinspection CallToPrintStackTrace -- will be picked up by logger
                e.printStackTrace();
            }
        });
    }

    private void init() {
        try {
            if(isClient() && targetAddress.isPresent()) {
                clientSocket = new Socket(targetAddress.get().getAddress(), targetAddress.get().getPort());
                configureClientSocket();
            }
            if(isServer()) initServerSocket();
        } catch (final IOException cause) {
            isInitializing.set(false);
            log.error("Failed to start IsAlive connection: {}", getRootCause(cause).getMessage());
            connectionIsLost();
        }
    }

    private void configureClientSocket() throws IOException {
        if(clientSocket == null) throw new IllegalStateException("Trying to initialize null client socket");
        clientSocket.setTcpNoDelay(true);
        clientSocket.setSoTimeout((int) maxSilenceDuration.toMillis());
        clientSocket.setSendBufferSize(1);
    }
    private void initServerSocket() throws IOException {
        serverSocket = new ServerSocket(0, 1);
        serverSocket.setSoTimeout((int) maxInitDuration.toMillis());
        serverSocket.setReceiveBufferSize(1);
        clientSocket = null;
        // setKeepAlive is not an option because that checks only every two hours (7200 sec)
    }

    private boolean isServer() { return targetAddress.isEmpty(); }
    private boolean isClient() { return targetAddress.isPresent(); }

    private void run() {
        try {
            isInitializing.set(false);
            if(isServer()) acceptConnection();
            isRunning.set(true);

            while(!stop.get()) {
                testConnection();
                shortSleep();
            }
        } catch(final Exception ignored) { // NOSONAR -- we also don't care about interruptions
            // Something failed. We don't care what failed
        } finally {
            isRunning.set(false);
        }
        cleanup();
        if(!stop.get()) connectionIsLost();
    }

    private void acceptConnection() throws IOException {
        if(serverSocket == null) throw new IllegalStateException("Attempt to accept connection on null server socket");
        clientSocket = serverSocket.accept();
        configureClientSocket();
    }

    private void cleanup() {
        if (clientSocket != null) noThrow(() -> clientSocket.close());
        if (serverSocket != null) noThrow(() -> serverSocket.close());
        clientSocket = null;
        serverSocket = null;
    }
    private void shortSleep() throws InterruptedException {
        synchronized (sync) {
            //noinspection UnconditionalWait
            sync.wait(maxSilenceDuration.toMillis() / 2); // NOSONAR this wait is called from a loop
        }
    }
    private void testConnection() throws IOException {
        if(clientSocket == null) throw new IOException("no connection");
        final OutputStream out = clientSocket.getOutputStream();
        out.write(IS_ALIVE_BYTE);
        out.flush();
        final byte response = (byte)clientSocket.getInputStream().read();
        if(IS_ALIVE_BYTE != response) {
            throw new IOException("Unexpected response: " + (char)response + "(" + response + ")");
        }
    }
}
