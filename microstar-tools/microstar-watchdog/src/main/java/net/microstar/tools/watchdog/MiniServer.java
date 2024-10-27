package net.microstar.tools.watchdog;

import net.microstar.common.util.ThreadBuilder;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static net.microstar.common.util.ExceptionUtils.noThrow;
import static net.microstar.common.util.Utils.sleep;

/** In some situations the Watchdog should provide a health endpoint that simply returns ok.
  * This requires a server-socket that listens on a provided port.
  * This implementation returns OK on any request.
  */
public class MiniServer {
    private final int port;
    private boolean stopped;
    private @Nullable ServerSocket serverSocket;

    public MiniServer(int port) { this.port = port; }

    public MiniServer start() {  new ThreadBuilder().name("HealthServer").isDaemon(true).run(this::run); return this; }
    public void stop() { stopped = true; close(); }
    public int port() { return serverSocket == null ? 0 : serverSocket.getLocalPort(); }

    public static void init(String... args) {
        Stream.of(args)
            .filter(arg->arg.startsWith("server="))
            .map(arg->arg.split("=",2)[1])
            .findFirst()
            .ifPresent(port -> new MiniServer(Integer.parseInt(port)).start());
    }

    private void run() {
        boolean initialized = false;
        while(!stopped) {
            if(!initialized) initialized = init(); else handleRequest();
        }
    }
    private boolean init() {
        close();
        try {
            serverSocket = new ServerSocket(port);
            Log.info("Opened server socket on port " + serverSocket.getLocalPort());
            return true;
        } catch(final IOException failed) {
            Log.error("Unable to create server socket on port " + port);
            sleep(2500);
            return false;
        }
    }
    private void close() {
        if(serverSocket != null) {
            noThrow(() -> serverSocket.close());
            serverSocket = null;
        }
    }
    private void handleRequest() {
        if(serverSocket != null) try {
            final Socket socket = serverSocket.accept();
            handle(socket);
        } catch (IOException e) { /*dontCare*/ }
    }
    private void handle(Socket socket) throws IOException {
        final InputStream in = socket.getInputStream();
        final String req = readLine(in); // METHOD URL HTTP/VERSION
        final int firstSpace = req.indexOf(' ');
        final int lastSpace = req.lastIndexOf(' ');
        final String url = req.substring(firstSpace, lastSpace).trim();
        while(!readLine(in).isEmpty()); // ignore headers

        socket.getOutputStream().write("""
            HTTP/1.1 200 OK
            Server: Watchdog
            
            """.replace("\n","\r\n").getBytes(StandardCharsets.UTF_8));
        socket.close();
    }
    private String readLine(InputStream in) throws IOException {
        final StringBuilder out = new StringBuilder();
        int i;
        while((i = in.read()) > 0 && i != '\n' && i != '\r') out.append((char)i);
        // line ends in CRLF (\r\n)
        if(i == '\r') in.read(); // skip LF
        return out.toString();
    }
}
