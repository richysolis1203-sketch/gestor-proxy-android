package com.gestorproxy.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLSocket;

/**
 * Puente local: un proxy HTTP que sólo escucha en 127.0.0.1. El navegador de la sesión apunta SIEMPRE aquí,
 * y el puente decide por dónde sale cada conexión:
 *
 * <ul>
 *   <li>PROXY: por el proxy de la sesión (HTTP, HTTPS o SOCKS5, con o sin usuario). Si el proxy falla NO se
 *       conecta directo: la conexión se rechaza.
 *   <li>DIRECT: sin proxy (el usuario lo pidió al apagar el interruptor).
 *   <li>BLOCKED: no sale nada.
 * </ul>
 */
public final class Bridge {
    public enum Mode {
        PROXY,
        DIRECT,
        BLOCKED
    }

    /** Configuración vigente. Se cambia de golpe (un solo objeto) para que nunca haya estados a medias. */
    public static final class Config {
        public final Mode mode;
        public final Upstream upstream;
        public final boolean blockTrackers;
        public final String reason;

        public Config(Mode mode, Upstream upstream, boolean blockTrackers, String reason) {
            this.mode = mode;
            this.upstream = upstream;
            this.blockTrackers = blockTrackers;
            this.reason = reason == null ? "" : reason;
        }
    }

    private static final int HEADER_TIMEOUT_MS = 30000;
    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int POLL_MS = 30000;
    private static final long IDLE_MS = 10L * 60L * 1000L;
    private static final int MAX_HEAD = 65536;
    private static final int MAX_HEADERS = 200;
    private static final int MAX_CONNECTIONS = 400;
    private static final byte[] CONNECT_OK = "HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

    /** Se llama antes de atender cada conexión nueva: permite releer la configuración si cambió desde otro proceso. */
    public interface Freshener {
        void beforeRequest();
    }

    private volatile Freshener freshener;
    private volatile Config cfg = new Config(Mode.BLOCKED, null, false, "Sin configurar.");
    private volatile boolean blockLocal = true;
    private volatile boolean stopped;
    /** Sube cada vez que cambia la ruta de salida (modo o proxy): las conexiones de la ruta anterior se cortan. */
    private final AtomicInteger routeEpoch = new AtomicInteger();
    private ServerSocket server;
    private Thread acceptThread;
    private final Set<Socket> active = Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
    private final Semaphore permits = new Semaphore(MAX_CONNECTIONS);
    private final ExecutorService pool;

    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong blockedTrackers = new AtomicLong();
    private final AtomicLong blockedOther = new AtomicLong();
    private volatile String lastError = "";
    private volatile long lastErrorAt = 0;

    public Bridge() {
        final AtomicInteger n = new AtomicInteger();
        pool = Executors.newCachedThreadPool(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "gp-bridge-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
    }

    // ------------------------------------------------------------------ control

    /** Empieza a escuchar en 127.0.0.1 (puerto al azar) y devuelve el puerto. */
    public synchronized int start() throws IOException {
        if (server != null) return server.getLocalPort();
        final ServerSocket ss = new ServerSocket(0, 128, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
        server = ss;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop(ss);
            }
        }, "gp-bridge-accept");
        t.setDaemon(true);
        acceptThread = t;
        t.start();
        return ss.getLocalPort();
    }

    /**
     * Cambia la configuración. Si cambia la RUTA de salida (modo o proxy), se cortan todas las conexiones abiertas:
     * así, al apagar o encender el interruptor, ningún túnel de la ruta anterior sigue funcionando.
     */
    public synchronized void configure(Mode mode, Upstream upstream, boolean blockTrackers, String reason) {
        Config next;
        if (mode == Mode.PROXY && upstream == null) {
            next = new Config(Mode.BLOCKED, null, blockTrackers, "Falta el proxy de la sesión.");
        } else {
            next = new Config(mode, upstream, blockTrackers, reason);
        }
        Config prev = cfg;
        cfg = next;
        if (!sameRoute(prev, next)) {
            routeEpoch.incrementAndGet();
            cutConnections();
        }
    }

    private static boolean sameRoute(Config a, Config b) {
        if (a.mode != b.mode) return false;
        if (a.mode != Mode.PROXY) return true;
        Upstream x = a.upstream;
        Upstream y = b.upstream;
        return x.protocol.equals(y.protocol) && x.host.equals(y.host) && x.port == y.port
                && x.username.equals(y.username) && x.password.equals(y.password);
    }

    private void cutConnections() {
        for (Socket s : new ArrayList<Socket>(active)) AddrUtil.closeQuietly(s);
    }

    public Config config() {
        return cfg;
    }

    public void setFreshener(Freshener f) {
        freshener = f;
    }

    /** Sólo para pruebas: permite destinos locales (127.0.0.1). En la app real siempre está activo el bloqueo. */
    public void setBlockLocal(boolean v) {
        blockLocal = v;
    }

    public synchronized void stop() {
        stopped = true;
        cfg = new Config(Mode.BLOCKED, null, false, "Cerrado.");
        AddrUtil.closeQuietly(server);
        // El cierre real del puerto ocurre cuando el hilo que acepta conexiones sale de accept(): se espera a que termine
        // para que, al volver de stop(), el puerto ya no acepte nada.
        if (acceptThread != null && acceptThread != Thread.currentThread()) {
            try {
                acceptThread.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        for (Socket s : new ArrayList<Socket>(active)) AddrUtil.closeQuietly(s);
        active.clear();
        pool.shutdownNow();
    }

    public String lastError() {
        return lastError;
    }

    public long lastErrorAt() {
        return lastErrorAt;
    }

    public long failures() {
        return failures.get();
    }

    public long trackersBlocked() {
        return blockedTrackers.get();
    }

    public long connectionsAccepted() {
        return accepted.get();
    }

    // ------------------------------------------------------------------ aceptar conexiones

    private void acceptLoop(ServerSocket ss) {
        while (!stopped) {
            final Socket c;
            try {
                c = ss.accept();
            } catch (IOException e) {
                if (stopped || ss.isClosed()) return;
                continue;
            }
            if (!permits.tryAcquire()) {
                AddrUtil.closeQuietly(c);
                continue;
            }
            accepted.incrementAndGet();
            try {
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            handle(c);
                        } catch (RuntimeException e) {
                            noteError("Error interno del puente: " + e);
                        } finally {
                            permits.release();
                            active.remove(c);
                            AddrUtil.closeQuietly(c);
                        }
                    }
                });
            } catch (RejectedExecutionException e) {
                permits.release();
                AddrUtil.closeQuietly(c);
            }
        }
    }

    private void noteError(String msg) {
        lastError = msg;
        lastErrorAt = System.currentTimeMillis();
        failures.incrementAndGet();
    }

    // ------------------------------------------------------------------ cabecera de la petición

    private static final class HeadException extends IOException {
        private static final long serialVersionUID = 1L;
        final int status;

        HeadException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private static final class Head {
        String method = "";
        String target = "";
        final List<String[]> headers = new ArrayList<String[]>();
        byte[] rest = new byte[0];

        boolean has(String lowerName) {
            for (String[] h : headers) {
                if (h[0].toLowerCase(Locale.ROOT).equals(lowerName)) return true;
            }
            return false;
        }

        static int indexOfHeaderEnd(byte[] b, int from, int to) {
            for (int i = Math.max(0, from); i + 3 < to; i++) {
                if (b[i] == '\r' && b[i + 1] == '\n' && b[i + 2] == '\r' && b[i + 3] == '\n') return i;
            }
            return -1;
        }

        /** Devuelve null si el cliente se desconectó sin enviar nada. */
        static Head read(InputStream in) throws IOException {
            byte[] buf = new byte[4096];
            int len = 0;
            int scanned = 0;
            while (true) {
                if (len == buf.length) {
                    if (len >= MAX_HEAD) throw new HeadException(431, "Cabecera demasiado grande.");
                    buf = Arrays.copyOf(buf, Math.min(buf.length * 2, MAX_HEAD));
                }
                int n = in.read(buf, len, buf.length - len);
                if (n < 0) {
                    if (len == 0) return null;
                    throw new HeadException(400, "Petición incompleta.");
                }
                len += n;
                int end = indexOfHeaderEnd(buf, scanned - 3, len);
                if (end >= 0) {
                    Head h = parse(new String(buf, 0, end, StandardCharsets.ISO_8859_1));
                    h.rest = Arrays.copyOfRange(buf, end + 4, len);
                    return h;
                }
                scanned = len;
            }
        }

        static Head parse(String text) throws HeadException {
            String[] lines = text.split("\r\n", -1);
            String[] first = lines[0].split(" ");
            if (first.length != 3 || first[0].isEmpty() || first[1].isEmpty() || !first[2].startsWith("HTTP/")) {
                throw new HeadException(400, "Petición no válida.");
            }
            Head h = new Head();
            h.method = first[0];
            h.target = first[1];
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                if (line.isEmpty()) continue;
                if ((line.charAt(0) == ' ' || line.charAt(0) == '\t') && !h.headers.isEmpty()) {
                    String[] prev = h.headers.get(h.headers.size() - 1);
                    prev[1] = prev[1] + " " + line.trim();
                    continue;
                }
                int c = line.indexOf(':');
                if (c <= 0) throw new HeadException(400, "Cabecera no válida.");
                if (h.headers.size() >= MAX_HEADERS) throw new HeadException(431, "Demasiadas cabeceras.");
                h.headers.add(new String[] {line.substring(0, c).trim(), line.substring(c + 1).trim()});
            }
            return h;
        }
    }

    private static final class HostPort {
        final String host;
        final int port;

        HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }

        /** «host:puerto», «[v6]:puerto» o «host» (con el puerto por omisión). Devuelve null si no es válido. */
        static HostPort parse(String s, int defPort) {
            if (s == null) return null;
            String t = s.trim();
            if (t.isEmpty() || t.length() > 300) return null;
            String host;
            int port = defPort;
            if (t.startsWith("[")) {
                int close = t.indexOf(']');
                if (close < 2) return null;
                host = t.substring(1, close);
                String rest = t.substring(close + 1);
                if (!rest.isEmpty()) {
                    if (!rest.startsWith(":")) return null;
                    port = parsePort(rest.substring(1));
                }
            } else {
                int c = t.lastIndexOf(':');
                if (c >= 0) {
                    if (t.indexOf(':') != c) return null; // IPv6 sin corchetes: no válido aquí
                    host = t.substring(0, c);
                    port = parsePort(t.substring(c + 1));
                } else {
                    host = t;
                }
            }
            if (host.isEmpty() || host.length() > Validate.HOST_MAX || port < 1 || port > 65535) return null;
            for (int i = 0; i < host.length(); i++) {
                char ch = host.charAt(i);
                if (ch <= ' ' || ch == '/' || ch == '\\' || ch == '@' || ch == 0x7f) return null;
            }
            return new HostPort(host, port);
        }

        private static int parsePort(String p) {
            if (!p.matches("^\\d{1,5}$")) return -1;
            return Integer.parseInt(p);
        }
    }

    // ------------------------------------------------------------------ una conexión

    private String deny(Config c, String host) {
        if (c.mode == Mode.BLOCKED) return c.reason.isEmpty() ? "Conexión bloqueada." : c.reason;
        if (blockLocal && AddrUtil.isLocalName(host)) {
            blockedOther.incrementAndGet();
            return "Destino local bloqueado: las sesiones no pueden abrir direcciones de tu propia red.";
        }
        if (c.blockTrackers && TrackerList.isTrackerHost(host)) {
            blockedTrackers.incrementAndGet();
            return "Rastreador bloqueado.";
        }
        return null;
    }

    private Socket openUpstream(Config c, String host, int port) throws IOException {
        switch (c.mode) {
            case PROXY:
                return c.upstream.connect(host, port, CONNECT_TIMEOUT_MS);
            case DIRECT:
                return openDirect(host, port);
            default:
                throw new ProxyException("BLOCKED", c.reason.isEmpty() ? "Conexión bloqueada." : c.reason);
        }
    }

    private Socket openDirect(String host, int port) throws IOException {
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw ProxyException.fromDirect(e);
        }
        IOException last = null;
        for (InetAddress a : addrs) {
            if (blockLocal && AddrUtil.isLocalAddress(a)) {
                blockedOther.incrementAndGet();
                throw new ProxyException("LOCAL", "Destino local bloqueado: las sesiones no pueden abrir direcciones de tu propia red.");
            }
            Socket s = new Socket();
            try {
                s.setTcpNoDelay(true);
                s.connect(new InetSocketAddress(a, port), CONNECT_TIMEOUT_MS);
                return s;
            } catch (IOException e) {
                AddrUtil.closeQuietly(s);
                last = e;
            }
        }
        throw ProxyException.fromDirect(last != null ? last : new UnknownHostException(host));
    }

    private void handle(Socket client) {
        Socket up = null;
        active.add(client);
        try {
            client.setTcpNoDelay(true);
            client.setSoTimeout(HEADER_TIMEOUT_MS);
            InputStream cin = client.getInputStream();
            OutputStream cout = client.getOutputStream();
            Head head;
            try {
                head = Head.read(cin);
            } catch (HeadException e) {
                reply(cout, e.status, e.status == 431 ? "Request Header Fields Too Large" : "Bad Request", e.getMessage());
                return;
            }
            if (head == null) return;
            Freshener fr = freshener;
            if (fr != null) {
                // Esta conexión es NUEVA: si el gancho cambia la ruta, no debe cortarse a sí misma (sí a las demás).
                active.remove(client);
                try {
                    fr.beforeRequest();
                } catch (RuntimeException e) {
                    noteError("No se pudo actualizar la configuración: " + e);
                } finally {
                    active.add(client);
                }
                if (stopped) return;
            }
            int epoch = routeEpoch.get(); // se lee ANTES que la configuración (ver configure)
            Config c = cfg;

            if ("CONNECT".equalsIgnoreCase(head.method)) {
                HostPort hp = HostPort.parse(head.target, 443);
                if (hp == null) {
                    reply(cout, 400, "Bad Request", "Destino no válido.");
                    return;
                }
                String deny = deny(c, hp.host);
                if (deny != null) {
                    reply(cout, 403, "Forbidden", deny);
                    return;
                }
                try {
                    up = openUpstream(c, hp.host, hp.port);
                } catch (IOException e) {
                    String msg = failMessage(c, e);
                    reply(cout, 502, "Bad Gateway", msg);
                    return;
                }
                active.add(up);
                if (routeEpoch.get() != epoch) {
                    reply(cout, 503, "Service Unavailable", ROUTE_CHANGED_MSG);
                    return;
                }
                cout.write(CONNECT_OK);
                cout.flush();
                if (head.rest.length > 0) {
                    OutputStream uo = up.getOutputStream();
                    uo.write(head.rest);
                    uo.flush();
                }
                relay(client, up);
                return;
            }

            // Petición normal (http://...): llega en forma absoluta.
            String t = head.target;
            if (t.length() < 8 || !t.regionMatches(true, 0, "http://", 0, 7)) {
                reply(cout, 400, "Bad Request", "Este puerto es el puente local de un proxy; no es un sitio web.");
                return;
            }
            String rest = t.substring(7);
            int end = indexOfAny(rest, "/?#");
            String authority = end < 0 ? rest : rest.substring(0, end);
            String path = end < 0 ? "/" : rest.substring(end);
            if (!path.startsWith("/")) path = "/" + path;
            int hash = path.indexOf('#');
            if (hash >= 0) path = path.substring(0, hash);
            int at = authority.lastIndexOf('@');
            if (at >= 0) authority = authority.substring(at + 1);
            HostPort hp = HostPort.parse(authority, 80);
            if (hp == null) {
                reply(cout, 400, "Bad Request", "Destino no válido.");
                return;
            }
            String deny = deny(c, hp.host);
            if (deny != null) {
                reply(cout, 403, "Forbidden", deny);
                return;
            }
            boolean viaHttpProxy = c.mode == Mode.PROXY && c.upstream.isHttpFamily();
            try {
                if (viaHttpProxy) {
                    up = c.upstream.openProxySocket(CONNECT_TIMEOUT_MS);
                    active.add(up);
                    checkRoute(epoch);
                    int th = t.indexOf('#');
                    String absolute = th >= 0 ? t.substring(0, th) : t;
                    writeRequest(up.getOutputStream(), head, absolute, c.upstream.proxyAuthorizationHeader());
                } else {
                    up = openUpstream(c, hp.host, hp.port);
                    active.add(up);
                    checkRoute(epoch);
                    writeRequest(up.getOutputStream(), head, path, "");
                }
                if (head.rest.length > 0) {
                    OutputStream uo = up.getOutputStream();
                    uo.write(head.rest);
                    uo.flush();
                }
            } catch (IOException e) {
                reply(cout, 502, "Bad Gateway", failMessage(c, e));
                return;
            }
            relay(client, up);
        } catch (IOException e) {
            // el navegador se fue: nada que hacer
        } finally {
            if (up != null) {
                active.remove(up);
                AddrUtil.closeQuietly(up);
            }
        }
    }

    private String failMessage(Config c, IOException e) {
        String msg;
        if (e instanceof ProxyException) {
            msg = e.getMessage();
        } else if (c.mode == Mode.PROXY) {
            msg = ProxyException.from(e, true).getMessage();
        } else {
            msg = ProxyException.fromDirect(e).getMessage();
        }
        if (!(e instanceof ProxyException) || !isQuietCode(((ProxyException) e).code)) noteError(msg);
        return msg;
    }

    private static boolean isQuietCode(String code) {
        return "BLOCKED".equals(code) || "ROUTE_CHANGED".equals(code);
    }

    private static final String ROUTE_CHANGED_MSG = "La ruta de salida cambió (interruptor del proxy); vuelve a intentarlo.";

    private void checkRoute(int epoch) throws ProxyException {
        if (routeEpoch.get() != epoch) throw new ProxyException("ROUTE_CHANGED", ROUTE_CHANGED_MSG);
    }

    private static int indexOfAny(String s, String chars) {
        for (int i = 0; i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) return i;
        }
        return -1;
    }

    private static void writeRequest(OutputStream out, Head head, String target, String extra) throws IOException {
        boolean upgrade = head.has("upgrade");
        StringBuilder sb = new StringBuilder();
        sb.append(head.method).append(' ').append(target).append(" HTTP/1.1\r\n");
        for (String[] h : head.headers) {
            String n = h[0].toLowerCase(Locale.ROOT);
            if (n.equals("proxy-connection") || n.equals("proxy-authorization") || n.equals("keep-alive")) continue;
            if (n.equals("connection") && !upgrade) continue;
            sb.append(h[0]).append(": ").append(h[1]).append("\r\n");
        }
        if (!upgrade) sb.append("Connection: close\r\n");
        sb.append(extra);
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static void reply(OutputStream out, int code, String reason, String text) {
        try {
            byte[] body = (text + "\n").getBytes(StandardCharsets.UTF_8);
            String head = "HTTP/1.1 " + code + " " + reason + "\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: "
                    + body.length + "\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
        } catch (IOException ignored) {
            // el navegador ya se fue
        }
    }

    // ------------------------------------------------------------------ copiar datos en ambos sentidos

    private void relay(final Socket client, final Socket up) {
        try {
            client.setSoTimeout(POLL_MS);
            up.setSoTimeout(POLL_MS);
        } catch (SocketException e) {
            return;
        }
        final AtomicLong last = new AtomicLong(System.currentTimeMillis());
        final AtomicBoolean upstreamDone = new AtomicBoolean(false);
        final CountDownLatch done = new CountDownLatch(1);
        try {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        copy(up, client, last, upstreamDone, true);
                    } finally {
                        upstreamDone.set(true);
                        done.countDown();
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            return;
        }
        copy(client, up, last, upstreamDone, false);
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void copy(Socket from, Socket to, AtomicLong last, AtomicBoolean upstreamDone, boolean fromUpstream) {
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            byte[] buf = new byte[16384];
            while (!stopped) {
                int n;
                try {
                    n = in.read(buf);
                } catch (SocketTimeoutException e) {
                    boolean idle = System.currentTimeMillis() - last.get() >= IDLE_MS;
                    if (idle || (!fromUpstream && upstreamDone.get())) break;
                    continue;
                }
                if (n < 0) break;
                out.write(buf, 0, n);
                out.flush();
                last.set(System.currentTimeMillis());
            }
        } catch (IOException ignored) {
            // uno de los dos lados se cerró
        } finally {
            halfClose(to);
        }
    }

    private static void halfClose(Socket s) {
        try {
            if (s instanceof SSLSocket) {
                AddrUtil.closeQuietly(s); // TLS no admite cerrar sólo la salida
            } else {
                s.shutdownOutput();
            }
        } catch (IOException e) {
            AddrUtil.closeQuietly(s);
        } catch (UnsupportedOperationException e) {
            AddrUtil.closeQuietly(s);
        }
    }
}
