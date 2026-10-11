package vn.edu.fs.server;

import vn.edu.fs.common.*;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Server TLS 1.3: accept -> giao ClientHandler cho thread pool. */
public final class FileServer implements AutoCloseable {
    public record Config(int port, Path keystore, char[] keystorePassword,
                         long maxFileBytes, int idleTimeoutMs, int threads) {}

    private final Config cfg;
    private final CommandDispatcher dispatcher;
    private final AuthService auth;
    private final AuditLogger audit;
    private SSLServerSocket serverSocket;
    private ExecutorService pool;
    private Thread acceptThread;

    public FileServer(Config cfg, AuthService auth, AclService acl, PathSandbox sandbox,
                      FileService files, AuditLogger audit) {
        this.cfg = cfg; this.auth = auth; this.audit = audit;
        this.dispatcher = new CommandDispatcher(auth, acl, sandbox, files, audit, cfg.maxFileBytes());
    }

    public void start() throws Exception {
        SSLContext ctx = TlsConfig.serverContext(cfg.keystore(), cfg.keystorePassword());
        serverSocket = (SSLServerSocket) ctx.getServerSocketFactory().createServerSocket(cfg.port());
        serverSocket.setEnabledProtocols(TlsConfig.PROTOCOLS);          // chi TLS 1.3
        pool = Executors.newFixedThreadPool(cfg.threads());
        acceptThread = new Thread(this::acceptLoop, "accept");
        acceptThread.start();
    }

    public int port() { return serverSocket.getLocalPort(); }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket s = serverSocket.accept();
                s.setSoTimeout(cfg.idleTimeoutMs());
                pool.execute(new ClientHandler(s, dispatcher, auth));     // handshake dien ra trong thread cua handler
            } catch (SocketException e) {
                break;                                                    // serverSocket da dong
            } catch (IOException e) {
                // loi accept tam thoi: bo qua, tiep tuc phuc vu
            }
        }
    }

    @Override public void close() throws Exception {
        if (serverSocket != null) serverSocket.close();
        if (pool != null) { pool.shutdownNow(); pool.awaitTermination(3, TimeUnit.SECONDS); }
        audit.close();
    }
}
