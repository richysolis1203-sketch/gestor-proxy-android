package com.gestorproxy.core;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Pruebas del núcleo en Java (sin Android, sin JUnit): servidores falsos en 127.0.0.1. */
public final class CoreTests {
    static int passed;
    static int failed;

    interface Body {
        void run() throws Exception;
    }

    static void test(String name, Body b) {
        try {
            b.run();
            passed++;
            System.out.println("ok   " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("FAIL " + name + ": " + t);
            t.printStackTrace(System.out);
        }
    }

    static void eq(Object expected, Object actual, String msg) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (!same) throw new AssertionError(msg + " — esperado <" + expected + "> pero fue <" + actual + ">");
    }

    static void ok(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    static void throwsCode(String code, Body b, String msg) {
        try {
            b.run();
        } catch (ProxyException e) {
            eq(code, e.code, msg + " (código; mensaje: " + e.getMessage() + ")");
            return;
        } catch (Throwable t) {
            throw new AssertionError(msg + " — se esperaba ProxyException(" + code + ") pero fue " + t);
        }
        throw new AssertionError(msg + " — se esperaba ProxyException(" + code + ") pero no hubo error");
    }

    // ================================================================== servidores falsos

    static ServerSocket loopback() throws IOException {
        return new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
    }

    abstract static class FakeServer implements Closeable {
        final ServerSocket ss;
        final AtomicInteger connections = new AtomicInteger();
        final List<Socket> open = Collections.synchronizedList(new ArrayList<Socket>());
        volatile boolean closed;

        FakeServer(ServerSocket ss) {
            this.ss = ss;
        }

        void start() {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (!closed) {
                        final Socket s;
                        try {
                            s = FakeServer.this.ss.accept();
                        } catch (IOException e) {
                            return;
                        }
                        connections.incrementAndGet();
                        open.add(s);
                        Thread w = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    serve(s);
                                } catch (Throwable ignored) {
                                    // los clientes de prueba pueden cortar
                                } finally {
                                    AddrUtil.closeQuietly(s);
                                }
                            }
                        });
                        w.setDaemon(true);
                        w.start();
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        }

        abstract void serve(Socket s) throws Exception;

        int port() {
            return ss.getLocalPort();
        }

        @Override
        public void close() {
            closed = true;
            AddrUtil.closeQuietly(ss);
            synchronized (open) {
                for (Socket s : open) AddrUtil.closeQuietly(s);
            }
        }
    }

    static byte[] readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0;
        while (true) {
            int c = in.read();
            if (c < 0) return null;
            b.write(c);
            if (c == '\r' && (state == 0 || state == 2)) state++;
            else if (c == '\n' && (state == 1 || state == 3)) state++;
            else state = (c == '\r') ? 1 : 0;
            if (state == 4) return b.toByteArray();
        }
    }

    static void pipe(final Socket a, final Socket b) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                copy(b, a);
            }
        });
        t.setDaemon(true);
        t.start();
        copy(a, b);
        try {
            t.join(5000);
        } catch (InterruptedException ignored) {
            // nada
        }
    }

    static void copy(Socket from, Socket to) {
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
            // fin
        } finally {
            try {
                to.shutdownOutput();
            } catch (Exception e) {
                AddrUtil.closeQuietly(to);
            }
        }
    }

    /** Repite lo que recibe. */
    static final class EchoServer extends FakeServer {
        EchoServer() throws IOException {
            super(loopback());
            start();
        }

        @Override
        void serve(Socket s) throws Exception {
            copy(s, s);
        }
    }

    /** Servidor web: responde con un cuerpo fijo y apunta las cabeceras recibidas. */
    static final class OriginServer extends FakeServer {
        volatile String body;
        final List<String> heads = Collections.synchronizedList(new ArrayList<String>());

        OriginServer(String body, SSLContext tls) throws IOException {
            super(tls == null ? loopback() : (ServerSocket) tls.getServerSocketFactory().createServerSocket(0, 50, InetAddress.getByName("127.0.0.1")));
            this.body = body;
            start();
        }

        @Override
        void serve(Socket s) throws Exception {
            byte[] h = readHead(s.getInputStream());
            if (h == null) return;
            String head = new String(h, StandardCharsets.ISO_8859_1);
            heads.add(head);
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            String resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n";
            OutputStream out = s.getOutputStream();
            out.write(resp.getBytes(StandardCharsets.ISO_8859_1));
            out.write(b);
            out.flush();
        }
    }

    /** Servidor web real: al recibir algo que no entiende responde 400 y cierra. */
    static final class JunkServer extends FakeServer {
        JunkServer() throws IOException {
            super(loopback());
            start();
        }

        @Override
        void serve(Socket s) throws Exception {
            s.getInputStream().read();
            s.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            s.getOutputStream().flush();
        }
    }

    /** Servidor SOCKS5 falso. Todo destino se redirige a 127.0.0.1:mapTo, pero apunta lo que le pidieron. */
    static final class FakeSocks5 extends FakeServer {
        volatile String user;
        volatile String pass;
        volatile int mapTo;
        volatile int failReply;
        final Set<String> failHosts = Collections.synchronizedSet(new HashSet<String>());
        final List<String> requests = Collections.synchronizedList(new ArrayList<String>());

        FakeSocks5(String user, String pass, int mapTo) throws IOException {
            super(loopback());
            this.user = user;
            this.pass = pass;
            this.mapTo = mapTo;
            start();
        }

        @Override
        void serve(Socket s) throws Exception {
            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            int ver = in.read();
            int n = in.read();
            if (ver != 5 || n < 0) return;
            byte[] methods = new byte[n];
            in.readFully(methods);
            boolean has0 = false;
            boolean has2 = false;
            for (byte m : methods) {
                if (m == 0) has0 = true;
                if (m == 2) has2 = true;
            }
            int chosen = user == null ? (has0 ? 0 : 0xff) : (has2 ? 2 : 0xff);
            out.write(new byte[] {5, (byte) chosen});
            out.flush();
            if (chosen == 0xff) return;
            if (chosen == 2) {
                in.read();
                byte[] u = new byte[in.read()];
                in.readFully(u);
                byte[] p = new byte[in.read()];
                in.readFully(p);
                boolean good = new String(u, StandardCharsets.UTF_8).equals(user) && new String(p, StandardCharsets.UTF_8).equals(pass);
                out.write(new byte[] {1, (byte) (good ? 0 : 1)});
                out.flush();
                if (!good) return;
            }
            byte[] head = new byte[4];
            in.readFully(head);
            int atyp = head[3];
            String host;
            if (atyp == 3) {
                byte[] d = new byte[in.read()];
                in.readFully(d);
                host = new String(d, StandardCharsets.UTF_8);
            } else if (atyp == 1) {
                byte[] a = new byte[4];
                in.readFully(a);
                host = InetAddress.getByAddress(a).getHostAddress();
            } else {
                byte[] a = new byte[16];
                in.readFully(a);
                host = InetAddress.getByAddress(a).getHostAddress();
            }
            int port = (in.read() << 8) | in.read();
            requests.add(atyp + ":" + host + ":" + port);
            if (failReply != 0 || failHosts.contains(host)) {
                out.write(new byte[] {5, (byte) (failReply != 0 ? failReply : 4), 0, 1, 0, 0, 0, 0, 0, 0});
                out.flush();
                return;
            }
            Socket t = new Socket("127.0.0.1", mapTo);
            out.write(new byte[] {5, 0, 0, 1, 0, 0, 0, 0, 0, 0});
            out.flush();
            pipe(s, t);
            AddrUtil.closeQuietly(t);
        }
    }

    /** Proxy HTTP falso (CONNECT y petición absoluta), con TLS opcional. */
    static final class FakeHttpProxy extends FakeServer {
        volatile String user;
        volatile String pass;
        volatile int mapTo;
        volatile int connectStatus = 200;
        final List<String> heads = Collections.synchronizedList(new ArrayList<String>());

        FakeHttpProxy(String user, String pass, int mapTo, SSLContext tls) throws IOException {
            super(tls == null ? loopback() : (ServerSocket) tls.getServerSocketFactory().createServerSocket(0, 50, InetAddress.getByName("127.0.0.1")));
            this.user = user;
            this.pass = pass;
            this.mapTo = mapTo;
            start();
        }

        @Override
        void serve(Socket s) throws Exception {
            byte[] h = readHead(s.getInputStream());
            if (h == null) return;
            String head = new String(h, StandardCharsets.ISO_8859_1);
            heads.add(head);
            OutputStream out = s.getOutputStream();
            if (user != null) {
                String expected = "Proxy-Authorization: Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
                if (!head.contains(expected)) {
                    out.write("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"t\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    return;
                }
            }
            if (head.startsWith("CONNECT ")) {
                if (connectStatus != 200) {
                    out.write(("HTTP/1.1 " + connectStatus + " Nope\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    return;
                }
                Socket t = new Socket("127.0.0.1", mapTo);
                out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                pipe(s, t);
                AddrUtil.closeQuietly(t);
            } else {
                byte[] b = ("PROXY-SAW:" + head).getBytes(StandardCharsets.ISO_8859_1);
                out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                out.write(b);
                out.flush();
            }
        }
    }

    // ================================================================== TLS de prueba

    static SSLContext serverTls;

    static void setupTls() throws Exception {
        File dir = Files.createTempDirectory("gp-tls").toFile();
        File ks = new File(dir, "ks.p12");
        Process p = new ProcessBuilder(
                        "keytool", "-genkeypair", "-alias", "t", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost",
                        "-validity", "2", "-keystore", ks.getAbsolutePath(), "-storetype", "PKCS12", "-storepass", "changeit",
                        "-keypass", "changeit", "-ext", "SAN=dns:localhost,ip:127.0.0.1")
                .redirectErrorStream(true)
                .start();
        InputStream o = p.getInputStream();
        byte[] sink = new byte[4096];
        while (o.read(sink) >= 0) {
            // consumir la salida
        }
        if (p.waitFor() != 0) throw new IllegalStateException("keytool falló");
        KeyStore store = KeyStore.getInstance("PKCS12");
        FileInputStream fis = new FileInputStream(ks);
        try {
            store.load(fis, "changeit".toCharArray());
        } finally {
            fis.close();
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, "changeit".toCharArray());
        serverTls = SSLContext.getInstance("TLS");
        serverTls.init(kmf.getKeyManagers(), null, new SecureRandom());

        SSLContext client = SSLContext.getInstance("TLS");
        client.init(null, new TrustManager[] {
            new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] c, String a) {}

                @Override
                public void checkServerTrusted(X509Certificate[] c, String a) {}

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }
        }, new SecureRandom());
        Tls.testFactory = client.getSocketFactory();
    }

    // ================================================================== ayudas de cliente

    static byte[] readAll(Socket s, int timeoutMs) throws IOException {
        s.setSoTimeout(timeoutMs);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        try {
            int n;
            while ((n = s.getInputStream().read(buf)) >= 0) b.write(buf, 0, n);
        } catch (SocketTimeoutException e) {
            // devolver lo recibido
        }
        return b.toByteArray();
    }

    static String text(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    static Socket toBridge(Bridge br, int port) throws IOException {
        Socket s = new Socket("127.0.0.1", port);
        s.setSoTimeout(8000);
        return s;
    }

    /** Envía una petición al puente y devuelve toda la respuesta hasta que se cierra. */
    static String bridgeRequest(int port, String request) throws IOException {
        Socket s = new Socket("127.0.0.1", port);
        try {
            try {
                s.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
                s.getOutputStream().flush();
            } catch (IOException cut) {
                // el puente puede cortar antes de que terminemos de enviar (por ejemplo, cabecera enorme)
            }
            try {
                return text(readAll(s, 8000));
            } catch (IOException reset) {
                return "";
            }
        } finally {
            s.close();
        }
    }

    /** Abre un CONNECT por el puente; devuelve la línea de estado y deja el socket listo (o lo cierra si falló). */
    static Object[] bridgeConnect(int port, String target) throws IOException {
        Socket s = new Socket("127.0.0.1", port);
        s.setSoTimeout(8000);
        s.getOutputStream().write(("CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\nProxy-Connection: keep-alive\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        s.getOutputStream().flush();
        byte[] h = readHead(s.getInputStream());
        if (h == null) {
            s.close();
            return new Object[] {"", null};
        }
        String head = new String(h, StandardCharsets.ISO_8859_1);
        String status = head.substring(0, head.indexOf("\r\n"));
        return new Object[] {status, s};
    }

    static String echoThrough(Socket s, String payload) throws IOException {
        s.getOutputStream().write(payload.getBytes(StandardCharsets.UTF_8));
        s.getOutputStream().flush();
        byte[] buf = new byte[payload.getBytes(StandardCharsets.UTF_8).length];
        new DataInputStream(s.getInputStream()).readFully(buf);
        return text(buf);
    }

    static Bridge newBridge() {
        Bridge b = new Bridge();
        b.setBlockLocal(false);
        return b;
    }

    // ================================================================== pruebas

    public static void main(String[] args) throws Exception {
        setupTls();

        jsonTests();
        validateTests();
        storeTests();
        addrTests();
        trackerTests();
        privacyTests();
        upstreamTests();
        bridgeTests();
        testerTests();

        System.out.println();
        System.out.println(passed + " pruebas correctas, " + failed + " con fallo.");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ JSON

    static void jsonTests() {
        test("Json: ida y vuelta con caracteres especiales", new Body() {
            public void run() throws Exception {
                java.util.Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
                m.put("a", "comillas \" barra \\ salto \n tab \t unicode ñ 日本 \u2028 control \u0001");
                m.put("n", Long.valueOf(42));
                m.put("d", Double.valueOf(1.5));
                m.put("b", Boolean.TRUE);
                m.put("z", null);
                m.put("l", Arrays.asList((Object) "x", Long.valueOf(2)));
                String text = Json.stringify(m);
                ok(text.indexOf('\n') < 0 && text.indexOf('\u2028') < 0, "el JSON no debe tener saltos de línea sin escapar");
                Map<String, Object> back = Json.obj(Json.parse(text));
                eq(m.get("a"), back.get("a"), "texto");
                eq(Long.valueOf(42), back.get("n"), "entero");
                eq(Double.valueOf(1.5), back.get("d"), "decimal");
                eq(Boolean.TRUE, back.get("b"), "booleano");
                ok(back.containsKey("z") && back.get("z") == null, "null");
                eq(2, Json.arr(back.get("l")).size(), "lista");
            }
        });
        test("Json: errores", new Body() {
            public void run() throws Exception {
                String[] bad = {"", "{", "[1,2", "{\"a\":}", "{\"a\" 1}", "[1,]x", "\"abc", "{\"a\":\"\\q\"}", "nul", "01x", "[1] 2", "\"\\u12\""};
                for (String b : bad) {
                    try {
                        Json.parse(b);
                        throw new AssertionError("debió fallar: " + b);
                    } catch (Json.JsonException expected) {
                        // bien
                    }
                }
                StringBuilder deep = new StringBuilder();
                for (int i = 0; i < 200; i++) deep.append('[');
                try {
                    Json.parse(deep.toString());
                    throw new AssertionError("debió fallar por anidación");
                } catch (Json.JsonException expected) {
                    // bien
                }
            }
        });
        test("Json: números", new Body() {
            public void run() throws Exception {
                eq(Long.valueOf(-5), Json.parse("-5"), "negativo");
                eq(Double.valueOf(1e3), Json.parse("1e3"), "exponente");
                eq(Double.valueOf(0.25), Json.parse("0.25"), "decimal");
                ok(Json.parse("123456789012345678901234567890") instanceof Double, "enorme -> Double");
            }
        });
    }

    // ------------------------------------------------------------------ Validate

    static void validateTests() {
        test("Validate: nombres", new Body() {
            public void run() {
                eq("Mi sesión", Validate.validateName("  Mi    sesión ", "x").value, "recorta y junta espacios");
                ok(!Validate.validateName("", "la sesión").ok(), "vacío");
                ok(!Validate.validateName("   ", "la sesión").ok(), "sólo espacios");
                ok(!Validate.validateName("a\u0000b", "x").ok(), "control");
                StringBuilder s = new StringBuilder();
                for (int i = 0; i < 61; i++) s.append('a');
                ok(!Validate.validateName(s.toString(), "x").ok(), "largo");
                ok(Validate.validateName(s.substring(1), "x").ok(), "60 caracteres sí");
            }
        });
        test("Validate: direcciones de proxy", new Body() {
            public void run() {
                eq("proxy.ejemplo.com", Validate.normalizeHost(" Proxy.Ejemplo.COM ").value, "minúsculas");
                eq("192.168.1.10", Validate.normalizeHost("192.168.1.10").value, "IPv4");
                eq("2001:db8::1", Validate.normalizeHost("[2001:DB8::1]").value, "IPv6 con corchetes");
                eq("::1", Validate.normalizeHost("::1").value, "IPv6 sin corchetes");
                eq("proxy.ejemplo.com", Validate.normalizeHost("http://proxy.ejemplo.com/ruta").value, "quita el esquema");
                eq("xn--bcher-kva.de", Validate.normalizeHost("bücher.de").value, "IDN");
                String[] bad = {"", "  ", "host:8080", "usuario@host", "999.1.1.1", "a b.com", "-malo.com", "malo-.com", "a..b", "host_raro.com", "1.2.3", "::::", "12345", "%41.com"};
                for (String b : bad) ok(!Validate.normalizeHost(b).ok(), "debe rechazar: '" + b + "'");
            }
        });
        test("Validate: puertos", new Body() {
            public void run() {
                eq("1080", Validate.normalizePort(" 1080 ").value, "ok");
                ok(!Validate.normalizePort("0").ok(), "0");
                ok(!Validate.normalizePort("65536").ok(), "65536");
                ok(!Validate.normalizePort("abc").ok(), "texto");
                ok(!Validate.normalizePort("").ok(), "vacío");
                ok(!Validate.normalizePort("-1").ok(), "negativo");
                ok(Validate.normalizePort("65535").ok(), "65535");
            }
        });
        test("Validate: formulario de proxy", new Body() {
            public void run() {
                Validate.ProxyChecked a = Validate.validateProxyInput("Mi proxy", "SOCKS5", "Host.com", "1080", " usuario ", "clave");
                ok(a.ok(), "debe ser válido: " + a.errors);
                eq("socks5", a.protocol, "protocolo en minúsculas");
                eq("usuario", a.username, "usuario recortado");
                eq(1080, a.port, "puerto");
                Validate.ProxyChecked b = Validate.validateProxyInput("", "ftp", "", "x", "", "clave");
                ok(!b.ok() && b.errors.containsKey("name") && b.errors.containsKey("protocol") && b.errors.containsKey("host")
                        && b.errors.containsKey("port") && b.errors.containsKey("username"), "todos los errores: " + b.errors);
                ok(!Validate.validateProxyInput("n", "http", "h.com", "80", "u\u0001x", "").ok(), "control en usuario");
            }
        });
        test("Validate: barra de direcciones", new Body() {
            public void run() {
                eq("https://example.com", Validate.normalizeUserUrl("example.com").url, "dominio");
                eq("url", Validate.normalizeUserUrl("example.com").kind, "tipo url");
                eq("https://ejemplo.com:8080/ruta", Validate.normalizeUserUrl("ejemplo.com:8080/ruta").url, "dominio con puerto");
                eq("http://localhost:3000", Validate.normalizeUserUrl("localhost:3000").url, "localhost");
                eq("http://192.168.1.1", Validate.normalizeUserUrl("192.168.1.1").url, "IP");
                eq("https://a.com/x", Validate.normalizeUserUrl("HTTPS://a.com/x").url, "esquema en mayúsculas");
                Validate.UrlResult s = Validate.normalizeUserUrl("hola mundo");
                eq("search", s.kind, "búsqueda");
                eq("https://duckduckgo.com/?q=hola%20mundo", s.url, "búsqueda codificada");
                eq("search", Validate.normalizeUserUrl("ejemplo").kind, "una palabra sin punto es búsqueda");
                String[] bad = {"", "javascript:alert(1)", "file:///etc/passwd", "ftp://x.com", "data:text/html,hola", "https://user:pw@x.com", "http://", "https://exa mple.com:99999", "intent://x#Intent;end", "about:blank"};
                for (String b : bad) ok(!Validate.normalizeUserUrl(b).ok, "debe rechazar: " + b);
                ok(Validate.isWebUrl("http://x.com") && Validate.isWebUrl("HTTPS://x.com") && !Validate.isWebUrl("javascript:1"), "isWebUrl");
            }
        });
        test("Validate: pegar un proxy", new Body() {
            public void run() {
                Validate.ParsedProxy a = Validate.parseProxyText("socks5://us%40er:p%3Ass@proxy.com:1080/");
                ok(a.ok(), "url completa");
                eq("socks5", a.protocol, "tipo");
                eq("us@er", a.username, "usuario decodificado");
                eq("p:ss", a.password, "clave decodificada");
                eq("proxy.com", a.host, "host");
                eq("1080", a.port, "puerto");
                Validate.ParsedProxy b = Validate.parseProxyText("1.2.3.4:8080");
                ok(b.ok() && b.protocol == null && "1.2.3.4".equals(b.host) && "8080".equals(b.port), "host:puerto");
                Validate.ParsedProxy c = Validate.parseProxyText("host.com:3128:bob:secreto");
                ok(c.ok() && "bob".equals(c.username) && "secreto".equals(c.password) && "3128".equals(c.port), "host:puerto:usuario:clave");
                Validate.ParsedProxy d = Validate.parseProxyText("bob:secreto@host.com:3128");
                ok(d.ok() && "bob".equals(d.username) && "host.com".equals(d.host), "usuario:clave@host:puerto");
                Validate.ParsedProxy e = Validate.parseProxyText("bob:secreto:host.com:3128");
                ok(e.ok() && "bob".equals(e.username) && "host.com".equals(e.host), "usuario:clave:host:puerto");
                Validate.ParsedProxy f = Validate.parseProxyText("http://[2001:db8::1]:8080");
                ok(f.ok() && "2001:db8::1".equals(f.host) && "http".equals(f.protocol), "IPv6");
                ok(!Validate.parseProxyText("socks4://h:1").ok(), "socks4 no soportado");
                ok(!Validate.parseProxyText("hola").ok(), "sin formato");
                ok(!Validate.parseProxyText("a b:1").ok(), "espacios");
                ok(!Validate.parseProxyText("").ok(), "vacío");
            }
        });
        test("Validate: IPv6 e IPv4", new Body() {
            public void run() {
                String[] good6 = {"::1", "::", "2001:db8::1", "fe80::1:2:3:4", "1:2:3:4:5:6:7:8", "::ffff:1.2.3.4", "1::", "2001:DB8:0:0:0:0:0:1"};
                for (String g : good6) ok(Validate.isIPv6(g), "IPv6 válida: " + g);
                String[] bad6 = {":::", "1:2:3:4:5:6:7", "1:2:3:4:5:6:7:8:9", "1::2::3", "g::1", "12345::1", "1.2.3.4", "::1.2.3", "fe80::1%eth0", ""};
                for (String g : bad6) ok(!Validate.isIPv6(g), "IPv6 inválida: " + g);
                ok(Validate.isIPv4("0.0.0.0") && Validate.isIPv4("255.255.255.255"), "IPv4 válidas");
                ok(!Validate.isIPv4("256.1.1.1") && !Validate.isIPv4("01.1.1.1") && !Validate.isIPv4("1.1.1") && !Validate.isIPv4("a.b.c.d"), "IPv4 inválidas");
            }
        });
        test("Validate: identificadores", new Body() {
            public void run() {
                ok(Validate.isProxyId(Validate.newId("p")), "proxy");
                ok(Validate.isSessionId(Validate.newId("s")), "sesión");
                ok(!Validate.isSessionId("s-../../etc"), "ruta maliciosa");
                ok(!Validate.isSessionId(null) && !Validate.isProxyId("p-XYZ"), "otros");
                ok(!Validate.newId("s").equals(Validate.newId("s")), "únicos");
            }
        });
    }

    // ------------------------------------------------------------------ Store

    static File tmpDir() throws IOException {
        return Files.createTempDirectory("gp-store").toFile();
    }

    static ProxyDef proxy(String name) {
        ProxyDef p = new ProxyDef();
        p.name = name;
        p.protocol = "socks5";
        p.host = "proxy.example.com";
        p.port = 1080;
        p.username = "u";
        p.passwordEnc = "v1:abc:def";
        return p;
    }

    static void storeTests() {
        test("Store: proxies y sesiones persisten", new Body() {
            public void run() throws Exception {
                File d = tmpDir();
                Store s = new Store(d);
                ProxyDef p = s.addProxy(proxy("Uno"));
                SessionDef proto = SessionDef.create("Sesión A", 0, 1000);
                proto.proxyId = p.id;
                proto.proxyEnabled = true;
                SessionDef a = s.addSession(proto);
                Store s2 = new Store(d);
                Store.Data data = s2.read();
                eq(1, data.proxies.size(), "un proxy");
                eq("Uno", data.proxies.get(0).name, "nombre del proxy");
                eq("v1:abc:def", data.proxies.get(0).passwordEnc, "contraseña cifrada intacta");
                eq(1, data.sessions.size(), "una sesión");
                SessionDef back = data.session(a.id);
                eq(p.id, back.proxyId, "proxy de la sesión");
                ok(back.proxyEnabled, "interruptor encendido");
                eq(a.seed, back.seed, "semilla estable");
            }
        });
        test("Store: máximo de sesiones y reutilización de procesos", new Body() {
            public void run() throws Exception {
                Store s = new Store(tmpDir());
                List<SessionDef> all = new ArrayList<SessionDef>();
                for (int i = 0; i < SessionDef.MAX_SESSIONS; i++) all.add(s.addSession(SessionDef.create("S" + i, 0, 1)));
                Set<Integer> slots = new HashSet<Integer>();
                for (SessionDef x : all) slots.add(x.slot);
                eq(SessionDef.MAX_SESSIONS, slots.size(), "cada sesión tiene un proceso distinto");
                try {
                    s.addSession(SessionDef.create("Extra", 0, 1));
                    throw new AssertionError("debió fallar con 10 sesiones");
                } catch (IOException e) {
                    ok(e.getMessage().contains("máximo"), "mensaje claro: " + e.getMessage());
                }
                int freed = all.get(3).slot;
                s.deleteSession(all.get(3).id);
                eq(freed, s.addSession(SessionDef.create("Nueva", 0, 1)).slot, "reusa el proceso liberado");
            }
        });
        test("Store: duplicar copia sólo la configuración", new Body() {
            public void run() throws Exception {
                Store s = new Store(tmpDir());
                SessionDef a = SessionDef.create("Original", 0, 1);
                a.blockTrackers = false;
                a.startUrl = "https://example.com";
                a = s.addSession(a);
                SessionDef c = s.duplicateSession(a.id);
                ok(!c.id.equals(a.id) && c.slot != a.slot && c.seed != a.seed, "id, proceso y huella distintos");
                eq("Original (copia)", c.name, "nombre");
                ok(!c.blockTrackers && "https://example.com".equals(c.startUrl), "ajustes copiados");
            }
        });
        test("Store: borrar un proxy deja las sesiones sin proxy", new Body() {
            public void run() throws Exception {
                Store s = new Store(tmpDir());
                ProxyDef p = s.addProxy(proxy("P"));
                SessionDef proto = SessionDef.create("S", 0, 1);
                proto.proxyId = p.id;
                proto.proxyEnabled = true;
                SessionDef a = s.addSession(proto);
                eq(1, s.deleteProxy(p.id), "sesiones afectadas");
                SessionDef back = s.read().session(a.id);
                ok(back.proxyId == null && !back.proxyEnabled, "sin proxy y apagado");
            }
        });
        test("Store: interruptor del proxy exige un proxy", new Body() {
            public void run() throws Exception {
                Store s = new Store(tmpDir());
                SessionDef a = s.addSession(SessionDef.create("S", 0, 1));
                try {
                    s.setProxyEnabled(a.id, true);
                    throw new AssertionError("debió fallar");
                } catch (IOException e) {
                    ok(e.getMessage().contains("proxy"), e.getMessage());
                }
                ProxyDef p = s.addProxy(proxy("P"));
                SessionDef edit = s.read().session(a.id);
                edit.proxyId = p.id;
                s.saveSession(edit);
                ok(s.setProxyEnabled(a.id, true).proxyEnabled, "ahora sí");
                ok(!s.setProxyEnabled(a.id, false).proxyEnabled, "apagar");
            }
        });
        test("Store: guardar una sesión no cambia su proceso", new Body() {
            public void run() throws Exception {
                Store s = new Store(tmpDir());
                SessionDef a = s.addSession(SessionDef.create("S", 0, 1));
                SessionDef edit = a.copy();
                edit.slot = 7;
                edit.name = "Renombrada";
                s.saveSession(edit);
                SessionDef back = s.read().session(a.id);
                eq(a.slot, back.slot, "proceso intacto");
                eq("Renombrada", back.name, "nombre nuevo");
            }
        });
        test("Store: escrituras simultáneas no pierden datos", new Body() {
            public void run() throws Exception {
                final Store s = new Store(tmpDir());
                final int threads = 8;
                final int each = 25;
                final CountDownLatch go = new CountDownLatch(1);
                final List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());
                List<Thread> ts = new ArrayList<Thread>();
                for (int t = 0; t < threads; t++) {
                    final int tt = t;
                    Thread th = new Thread(new Runnable() {
                        public void run() {
                            try {
                                go.await();
                                for (int i = 0; i < each; i++) s.addProxy(proxy("P" + tt + "-" + i));
                            } catch (Throwable e) {
                                errors.add(e);
                            }
                        }
                    });
                    ts.add(th);
                    th.start();
                }
                go.countDown();
                for (Thread th : ts) th.join();
                ok(errors.isEmpty(), "sin errores: " + errors);
                eq(threads * each, s.read().proxies.size(), "ningún proxy perdido");
            }
        });
        test("Store: archivo dañado se recupera de la copia", new Body() {
            public void run() throws Exception {
                File d = tmpDir();
                Store s = new Store(d);
                s.addProxy(proxy("Uno"));
                s.addProxy(proxy("Dos")); // ahora hay copia de respaldo con «Uno»
                Files.write(new File(d, "store.json").toPath(), "{esto no es json".getBytes(StandardCharsets.UTF_8));
                Store.Data data = s.read();
                ok(data.note != null, "avisa de la recuperación");
                eq(1, data.proxies.size(), "recupera la copia");
                s.addProxy(proxy("Tres"));
                Store.Data after = s.read();
                ok(after.note == null, "ya no hay aviso");
                eq(2, after.proxies.size(), "sigue funcionando tras recuperar");
            }
        });
        test("Store: registros manipulados se ignoran", new Body() {
            public void run() throws Exception {
                File d = tmpDir();
                String evil = "{\"version\":1,\"proxies\":[{\"id\":\"p-../../x\",\"name\":\"x\",\"protocol\":\"socks5\",\"host\":\"h\",\"port\":1}],"
                        + "\"sessions\":[{\"id\":\"s-../../../etc\",\"name\":\"x\",\"slot\":0},{\"id\":\"s-0123456789abcdef\",\"name\":\"ok\",\"slot\":99},"
                        + "{\"id\":\"s-0123456789abcdef\",\"name\":\"bien\",\"slot\":2,\"proxyId\":\"p-ffffffffffffffff\",\"proxyEnabled\":true,\"startUrl\":\"javascript:alert(1)\"}]}";
                Files.write(new File(d, "store.json").toPath(), evil.getBytes(StandardCharsets.UTF_8));
                Store.Data data = new Store(d).read();
                eq(0, data.proxies.size(), "proxy con id falso ignorado");
                eq(1, data.sessions.size(), "sólo la sesión válida");
                SessionDef s = data.sessions.get(0);
                ok(s.proxyId == null && !s.proxyEnabled, "proxy inexistente");
                eq("", s.startUrl, "url peligrosa descartada");
            }
        });
        test("Store: sin archivo está vacío", new Body() {
            public void run() throws Exception {
                Store.Data d = new Store(tmpDir()).read();
                ok(d.proxies.isEmpty() && d.sessions.isEmpty() && d.note == null, "vacío y sin aviso");
            }
        });
    }

    // ------------------------------------------------------------------ AddrUtil

    static void addrTests() {
        test("AddrUtil: destinos locales", new Body() {
            public void run() {
                String[] local = {"localhost", "LOCALHOST", "foo.localhost", "127.0.0.1", "127.5.5.5", "10.1.2.3", "192.168.0.1", "172.16.5.5",
                    "172.31.255.255", "169.254.1.1", "0.0.0.0", "100.64.0.1", "::1", "fd00::1", "fe80::1", "foo.local", "router.lan", "0x7f.1",
                    "2130706433", "::ffff:127.0.0.1", "224.0.0.1", ""};
                for (String h : local) ok(AddrUtil.isLocalName(h), "debe ser local: '" + h + "'");
                String[] pub = {"example.com", "8.8.8.8", "172.32.0.1", "100.128.0.1", "2001:db8::1", "1.1.1.1", "sub.dominio.es"};
                for (String h : pub) ok(!AddrUtil.isLocalName(h), "debe ser público: " + h);
            }
        });
        test("AddrUtil: borrar carpetas no sigue enlaces", new Body() {
            public void run() throws Exception {
                File outside = Files.createTempDirectory("gp-outside").toFile();
                File keep = new File(outside, "keep.txt");
                Files.write(keep.toPath(), "importante".getBytes(StandardCharsets.UTF_8));
                File dir = Files.createTempDirectory("gp-del").toFile();
                File sub = new File(dir, "sub");
                sub.mkdir();
                Files.write(new File(sub, "a.txt").toPath(), "x".getBytes(StandardCharsets.UTF_8));
                Files.createSymbolicLink(new File(dir, "enlace").toPath(), outside.toPath());
                ok(AddrUtil.deleteRecursively(dir), "borrado");
                ok(!dir.exists(), "la carpeta ya no existe");
                ok(keep.exists(), "el archivo de fuera sigue ahí");
            }
        });
    }

    // ------------------------------------------------------------------ TrackerList

    static void trackerTests() {
        test("TrackerList: dominios", new Body() {
            public void run() {
                eq(195, TrackerList.size(), "195 dominios");
                ok(TrackerList.isTrackerHost("google-analytics.com"), "exacto");
                ok(TrackerList.isTrackerHost("www.google-analytics.com"), "subdominio");
                ok(TrackerList.isTrackerHost("A.B.DoubleClick.net"), "mayúsculas y profundidad");
                ok(TrackerList.isTrackerHost("doubleclick.net."), "punto final");
                ok(!TrackerList.isTrackerHost("example.com"), "otro");
                ok(!TrackerList.isTrackerHost("notgoogle-analytics.com"), "no es subdominio");
                ok(!TrackerList.isTrackerHost("com") && !TrackerList.isTrackerHost("") && !TrackerList.isTrackerHost(null), "casos raros");
            }
        });
        test("TrackerList: URLs y reglas por ruta", new Body() {
            public void run() {
                ok(TrackerList.isTrackerUrl("https://www.google-analytics.com/collect?v=1"), "por dominio");
                ok(TrackerList.isTrackerUrl("https://connect.facebook.net/en_US/fbevents.js"), "fbevents");
                ok(!TrackerList.isTrackerUrl("https://connect.facebook.net/en_US/sdk.js"), "sdk legítimo");
                ok(TrackerList.isTrackerUrl("https://www.facebook.com/tr?id=1&ev=PageView"), "píxel de facebook");
                ok(!TrackerList.isTrackerUrl("https://www.facebook.com/profile"), "facebook normal");
                ok(!TrackerList.isTrackerUrl("https://www.facebook.com/transparency"), "prefijo /tr no coincide con /transparency");
                ok(TrackerList.isTrackerUrl("https://www.google.com/pagead/1p-user-list/1"), "pagead");
                ok(!TrackerList.isTrackerUrl("https://www.google.com/search?q=hola"), "búsqueda de google");
                ok(TrackerList.isTrackerUrl("https://www.linkedin.com/px/li_sync"), "linkedin px");
                ok(!TrackerList.isTrackerUrl("https://example.com/?u=google-analytics.com"), "sólo cuenta el host");
                ok(!TrackerList.isTrackerUrl("https://user:pw@example.com@evil/"), "no se deja engañar por usuario@");
                ok(TrackerList.isTrackerUrl("https://doubleclick.net:8443/x"), "con puerto");
                ok(!TrackerList.isTrackerUrl("no es url"), "texto suelto");
            }
        });
    }

    // ------------------------------------------------------------------ Privacy

    static void privacyTests() {
        test("Privacy: configuración y semilla por sesión", new Body() {
            public void run() throws Exception {
                SessionDef a = SessionDef.create("A", 0, 1);
                SessionDef b = SessionDef.create("B", 1, 1);
                Map<String, Object> ca = Json.obj(Json.parse(Privacy.contentConfigJson(a)));
                Map<String, Object> cb = Json.obj(Json.parse(Privacy.contentConfigJson(b)));
                ok(!ca.get("seed").equals(cb.get("seed")), "huella distinta entre sesiones");
                eq(Boolean.TRUE, ca.get("canvas"), "canvas");
                a.fingerprintProtection = false;
                Map<String, Object> off = Json.obj(Json.parse(Privacy.contentConfigJson(a)));
                eq(Boolean.FALSE, off.get("canvas"), "canvas apagado");
                eq(Boolean.TRUE, off.get("gpc"), "señal de privacidad sigue");
                eq(0, Json.arr(off.get("languages")).size(), "no se cambia el idioma");
            }
        });
        test("Privacy: guion de inicio de página", new Body() {
            public void run() {
                SessionDef s = SessionDef.create("A", 0, 1);
                String all = Privacy.buildScript("function gpProtect(cfg){}", "(function(){})();", s);
                ok(all.contains("gpProtect") && all.contains("(function(){})();"), "incluye ambas partes");
                s.webrtcProtection = false;
                ok(!Privacy.buildScript("function gpProtect(cfg){}", "WEBRTC", s).contains("WEBRTC"), "sin WebRTC");
                s.fingerprintProtection = false;
                s.sendPrivacySignals = false;
                ok(!Privacy.buildScript("function gpProtect(cfg){}", "WEBRTC", s).contains("gpProtect"), "sin huella ni señales");
                ok(!Privacy.needsDocumentStartScript(s), "nada que inyectar");
                s.webrtcProtection = true;
                ok(Privacy.needsDocumentStartScript(s), "WebRTC exige guion");
            }
        });
        test("Privacy: User-Agent reducido", new Body() {
            public void run() {
                String real = "Mozilla/5.0 (Linux; Android 13; Pixel 7 Build/TQ3A.230901.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/119.0.6045.163 Mobile Safari/537.36";
                eq("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36",
                        Privacy.reduceUserAgent(real), "móvil");
                String tablet = "Mozilla/5.0 (Linux; Android 13; SM-X700 Build/TP1A; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.0.0 Safari/537.36";
                ok(!Privacy.reduceUserAgent(tablet).contains("Mobile"), "tablet sin Mobile");
                eq("raro (Linux)", Privacy.reduceUserAgent("raro (Linux; wv)"), "sin Chrome: sólo quita wv");
                ok(!Privacy.reduceUserAgent(real).contains("Pixel") && !Privacy.reduceUserAgent(real).contains("wv"), "sin modelo ni wv");
            }
        });
        test("Privacy: páginas HTML escapan el texto", new Body() {
            public void run() {
                String page = Privacy.startPageHtml("<script>alert(1)</script>");
                ok(!page.contains("<script>alert") && page.contains("&lt;script&gt;"), "escapado");
                ok(!Privacy.errorPageHtml("t<", "d\"&").contains("t<<"), "error escapado");
            }
        });
    }

    // ------------------------------------------------------------------ Upstream (SOCKS5 y HTTP)

    static void upstreamTests() {
        test("SOCKS5: sin usuario, el nombre llega como dominio (sin DNS local)", new Body() {
            public void run() throws Exception {
                EchoServer echo = new EchoServer();
                FakeSocks5 socks = new FakeSocks5(null, null, echo.port());
                try {
                    Upstream up = new Upstream("socks5", "127.0.0.1", socks.port(), "", "");
                    Socket s = up.connect("origen.no-existe.test", 443, 5000);
                    eq("hola mundo", echoThrough(s, "hola mundo"), "datos por el túnel");
                    s.close();
                    eq("3:origen.no-existe.test:443", socks.requests.get(0), "el proxy recibió el nombre, no una IP");
                } finally {
                    socks.close();
                    echo.close();
                }
            }
        });
        test("SOCKS5: con usuario y contraseña", new Body() {
            public void run() throws Exception {
                EchoServer echo = new EchoServer();
                FakeSocks5 socks = new FakeSocks5("ana", "sécreto ñ", echo.port());
                try {
                    Socket s = new Upstream("socks5", "127.0.0.1", socks.port(), "ana", "sécreto ñ").connect("x.test", 80, 5000);
                    eq("ok", echoThrough(s, "ok"), "eco");
                    s.close();
                } finally {
                    socks.close();
                    echo.close();
                }
            }
        });
        test("SOCKS5: errores claros", new Body() {
            public void run() throws Exception {
                EchoServer echo = new EchoServer();
                final FakeSocks5 socks = new FakeSocks5("ana", "bien", echo.port());
                try {
                    throwsCode("AUTH_FAILED", new Body() {
                        public void run() throws Exception {
                            new Upstream("socks5", "127.0.0.1", socks.port(), "ana", "mal").connect("x.test", 80, 5000);
                        }
                    }, "contraseña incorrecta");
                    throwsCode("AUTH_REQUIRED", new Body() {
                        public void run() throws Exception {
                            new Upstream("socks5", "127.0.0.1", socks.port(), "", "").connect("x.test", 80, 5000);
                        }
                    }, "falta usuario");
                    socks.failReply = 5;
                    throwsCode("REPLY_5", new Body() {
                        public void run() throws Exception {
                            new Upstream("socks5", "127.0.0.1", socks.port(), "ana", "bien").connect("x.test", 80, 5000);
                        }
                    }, "el destino rechazó");
                    ok(Upstream.explainReply(5).contains("rechazó"), "texto del error 5");
                } finally {
                    socks.close();
                    echo.close();
                }
            }
        });
        test("SOCKS5: direcciones IP literales (IPv4 e IPv6)", new Body() {
            public void run() throws Exception {
                EchoServer echo = new EchoServer();
                FakeSocks5 socks = new FakeSocks5(null, null, echo.port());
                try {
                    Upstream up = new Upstream("socks5", "127.0.0.1", socks.port(), "", "");
                    up.connect("93.184.216.34", 80, 5000).close();
                    up.connect("2001:db8::5", 443, 5000).close();
                    eq("1:93.184.216.34:80", socks.requests.get(0), "IPv4 como ATYP 1");
                    ok(socks.requests.get(1).startsWith("4:") && socks.requests.get(1).endsWith(":443"), "IPv6 como ATYP 4: " + socks.requests.get(1));
                } finally {
                    socks.close();
                    echo.close();
                }
            }
        });
        test("Proxy inalcanzable, mudo o de otro tipo", new Body() {
            public void run() throws Exception {
                ServerSocket closedPort = loopback();
                final int dead = closedPort.getLocalPort();
                closedPort.close();
                throwsCode("REFUSED", new Body() {
                    public void run() throws Exception {
                        new Upstream("socks5", "127.0.0.1", dead, "", "").connect("x.test", 80, 3000);
                    }
                }, "puerto cerrado");
                throwsCode("DNS", new Body() {
                    public void run() throws Exception {
                        new Upstream("socks5", "no-existe.invalid", 1080, "", "").connect("x.test", 80, 3000);
                    }
                }, "nombre inexistente");
                final ServerSocket mute = loopback();
                Thread t = new Thread(new Runnable() {
                    public void run() {
                        try {
                            mute.accept();
                            Thread.sleep(5000);
                        } catch (Exception ignored) {
                            // fin
                        }
                    }
                });
                t.setDaemon(true);
                t.start();
                try {
                    throwsCode("TIMEOUT", new Body() {
                        public void run() throws Exception {
                            new Upstream("socks5", "127.0.0.1", mute.getLocalPort(), "", "").connect("x.test", 80, 600);
                        }
                    }, "servidor mudo");
                } finally {
                    mute.close();
                }
                JunkServer web = new JunkServer();
                try {
                    final int wp = web.port();
                    throwsCode("BAD_PROTOCOL", new Body() {
                        public void run() throws Exception {
                            new Upstream("socks5", "127.0.0.1", wp, "", "").connect("x.test", 80, 3000);
                        }
                    }, "un servidor web no es SOCKS5");
                } finally {
                    web.close();
                }
                FakeSocks5 socks = new FakeSocks5(null, null, 1);
                try {
                    final int sp = socks.port();
                    throwsCode("CLOSED", new Body() {
                        public void run() throws Exception {
                            new Upstream("http", "127.0.0.1", sp, "", "").connect("x.test", 80, 3000);
                        }
                    }, "un servidor SOCKS cierra sin contestar a una petición HTTP");
                } finally {
                    socks.close();
                }
            }
        });
        test("HTTP CONNECT: usuario, errores y datos", new Body() {
            public void run() throws Exception {
                EchoServer echo = new EchoServer();
                final FakeHttpProxy px = new FakeHttpProxy("bob", "pw:con:dos puntos", echo.port(), null);
                try {
                    Socket s = new Upstream("http", "127.0.0.1", px.port(), "bob", "pw:con:dos puntos").connect("sitio.test", 443, 5000);
                    eq("datos", echoThrough(s, "datos"), "eco por CONNECT");
                    s.close();
                    ok(px.heads.get(0).startsWith("CONNECT sitio.test:443 HTTP/1.1"), "línea CONNECT: " + px.heads.get(0));
                    throwsCode("AUTH_FAILED", new Body() {
                        public void run() throws Exception {
                            new Upstream("http", "127.0.0.1", px.port(), "bob", "mal").connect("x.test", 443, 5000);
                        }
                    }, "credenciales malas");
                    throwsCode("AUTH_REQUIRED", new Body() {
                        public void run() throws Exception {
                            new Upstream("http", "127.0.0.1", px.port(), "", "").connect("x.test", 443, 5000);
                        }
                    }, "faltan credenciales");
                    px.user = null;
                    px.connectStatus = 403;
                    throwsCode("FORBIDDEN", new Body() {
                        public void run() throws Exception {
                            new Upstream("http", "127.0.0.1", px.port(), "", "").connect("x.test", 443, 5000);
                        }
                    }, "403");
                    px.connectStatus = 502;
                    throwsCode("BAD_GATEWAY", new Body() {
                        public void run() throws Exception {
                            new Upstream("http", "127.0.0.1", px.port(), "", "").connect("x.test", 443, 5000);
                        }
                    }, "502");
                } finally {
                    px.close();
                    echo.close();
                }
            }
        });
        test("HTTPS (TLS hacia el propio proxy)", new Body() {
            public void run() throws Exception {
                EchoServer echo = new EchoServer();
                FakeHttpProxy px = new FakeHttpProxy("ana", "x", echo.port(), serverTls);
                try {
                    Socket s = new Upstream("https", "127.0.0.1", px.port(), "ana", "x").connect("sitio.test", 443, 5000);
                    eq("seguro", echoThrough(s, "seguro"), "eco por TLS+CONNECT");
                    s.close();
                } finally {
                    px.close();
                    echo.close();
                }
                // Un proxy que no habla TLS, contactado como HTTPS:
                final JunkServer plain = new JunkServer();
                try {
                    throwsCode("TLS_PROTO", new Body() {
                        public void run() throws Exception {
                            new Upstream("https", "127.0.0.1", plain.port(), "", "").connect("x.test", 443, 3000);
                        }
                    }, "proxy sin TLS");
                } finally {
                    plain.close();
                    echo.close();
                }
            }
        });
    }

    // ------------------------------------------------------------------ Bridge

    static void bridgeTests() {
        test("Puente: CONNECT por SOCKS5 llega con el nombre y copia datos", new Body() {
            public void run() throws Exception {
                EchoServer echo = new EchoServer();
                FakeSocks5 socks = new FakeSocks5("u", "p", echo.port());
                Bridge br = newBridge();
                try {
                    int port = br.start();
                    br.configure(Bridge.Mode.PROXY, new Upstream("socks5", "127.0.0.1", socks.port(), "u", "p"), false, "");
                    Object[] r = bridgeConnect(port, "ejemplo.test:443");
                    eq("HTTP/1.1 200 Connection Established", r[0], "estado");
                    Socket s = (Socket) r[1];
                    eq("ping", echoThrough(s, "ping"), "eco");
                    s.close();
                    eq("3:ejemplo.test:443", socks.requests.get(0), "dominio al proxy");
                } finally {
                    br.stop();
                    socks.close();
                    echo.close();
                }
            }
        });
        test("Puente: si el proxy falla NO sale directo", new Body() {
            public void run() throws Exception {
                EchoServer origin = new EchoServer(); // sería el destino real si se saltara el proxy
                ServerSocket closed = loopback();
                int deadPort = closed.getLocalPort();
                closed.close();
                Bridge br = newBridge();
                try {
                    int port = br.start();
                    br.configure(Bridge.Mode.PROXY, new Upstream("socks5", "127.0.0.1", deadPort, "", ""), false, "");
                    Object[] r = bridgeConnect(port, "127.0.0.1:" + origin.port());
                    ok(((String) r[0]).contains("502"), "502 (era: " + r[0] + ")");
                    eq(0, origin.connections.get(), "el destino NO recibió ninguna conexión directa");
                    ok(br.lastError().contains("rechazó"), "explica el fallo: " + br.lastError());
                    String plain = bridgeRequest(port, "GET http://127.0.0.1:" + origin.port() + "/ HTTP/1.1\r\nHost: x\r\n\r\n");
                    ok(plain.startsWith("HTTP/1.1 502"), "también para http: " + plain);
                    eq(0, origin.connections.get(), "tampoco por http");
                } finally {
                    br.stop();
                    origin.close();
                }
            }
        });
        test("Puente: bloqueado no abre nada; cambiar de modo en caliente", new Body() {
            public void run() throws Exception {
                EchoServer echo = new EchoServer();
                FakeSocks5 socks = new FakeSocks5(null, null, echo.port());
                Bridge br = newBridge();
                try {
                    int port = br.start();
                    String res = bridgeRequest(port, "CONNECT a.test:443 HTTP/1.1\r\nHost: a.test:443\r\n\r\n");
                    ok(res.startsWith("HTTP/1.1 403"), "recién creado está bloqueado");
                    br.configure(Bridge.Mode.PROXY, new Upstream("socks5", "127.0.0.1", socks.port(), "", ""), false, "");
                    Object[] r = bridgeConnect(port, "a.test:443");
                    ok(((String) r[0]).contains("200"), "por proxy");
                    ((Socket) r[1]).close();
                    int seen = socks.requests.size();
                    br.configure(Bridge.Mode.BLOCKED, null, false, "Proxy apagado: sin conexión.");
                    String blocked = bridgeRequest(port, "CONNECT a.test:443 HTTP/1.1\r\nHost: a.test:443\r\n\r\n");
                    ok(blocked.startsWith("HTTP/1.1 403") && blocked.contains("Proxy apagado"), "bloqueado con motivo: " + blocked);
                    eq(seen, socks.requests.size(), "el proxy no vio nada nuevo");
                    br.configure(Bridge.Mode.PROXY, null, false, "");
                    ok(br.config().mode == Bridge.Mode.BLOCKED, "PROXY sin proxy se convierte en bloqueado");
                } finally {
                    br.stop();
                    socks.close();
                    echo.close();
                }
            }
        });
        test("Puente: cambiar la ruta corta los túneles abiertos (y cambiar otra cosa no)", new Body() {
            public void run() throws Exception {
                EchoServer echo = new EchoServer();
                FakeSocks5 socks = new FakeSocks5(null, null, echo.port());
                Bridge br = newBridge();
                try {
                    int port = br.start();
                    br.configure(Bridge.Mode.DIRECT, null, false, "");
                    Object[] r = bridgeConnect(port, "127.0.0.1:" + echo.port());
                    Socket direct = (Socket) r[1];
                    eq("uno", echoThrough(direct, "uno"), "el túnel directo funciona");

                    // Cambiar sólo el bloqueo de rastreadores NO cambia la ruta: el túnel sigue vivo.
                    br.configure(Bridge.Mode.DIRECT, null, true, "");
                    eq("dos", echoThrough(direct, "dos"), "el túnel sigue vivo si la ruta es la misma");

                    // Pasar a proxy SÍ cambia la ruta: el túnel directo se corta (no puede seguir mostrando la IP real).
                    br.configure(Bridge.Mode.PROXY, new Upstream("socks5", "127.0.0.1", socks.port(), "", ""), true, "");
                    direct.setSoTimeout(3000);
                    int n;
                    try {
                        n = direct.getInputStream().read();
                    } catch (java.io.IOException e) {
                        n = -1;
                    }
                    eq(-1, n, "el túnel directo se cortó al encender el proxy");

                    // El túnel nuevo va por el proxy y también se corta al apagar el interruptor.
                    Object[] r2 = bridgeConnect(port, "ejemplo.test:443");
                    Socket viaProxy = (Socket) r2[1];
                    eq("tres", echoThrough(viaProxy, "tres"), "por proxy");
                    br.configure(Bridge.Mode.BLOCKED, null, true, "Proxy apagado: sin conexión.");
                    viaProxy.setSoTimeout(3000);
                    try {
                        n = viaProxy.getInputStream().read();
                    } catch (java.io.IOException e) {
                        n = -1;
                    }
                    eq(-1, n, "el túnel por proxy se cortó al bloquear");
                    String blocked = bridgeRequest(port, "CONNECT a.test:443 HTTP/1.1\r\nHost: a.test:443\r\n\r\n");
                    ok(blocked.startsWith("HTTP/1.1 403"), "y no se abre nada nuevo: " + blocked);
                    direct.close();
                    viaProxy.close();
                } finally {
                    br.stop();
                    socks.close();
                    echo.close();
                }
            }
        });
        test("Puente: el gancho de actualización cambia la ruta sin cortar la conexión nueva", new Body() {
            public void run() throws Exception {
                EchoServer echo = new EchoServer();
                FakeSocks5 socks = new FakeSocks5(null, null, echo.port());
                final Bridge br = newBridge();
                try {
                    int port = br.start();
                    br.configure(Bridge.Mode.BLOCKED, null, false, "Proxy apagado.");
                    final int socksPort = socks.port();
                    final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
                    br.setFreshener(new Bridge.Freshener() {
                        public void beforeRequest() {
                            // simula que el interruptor se encendió desde el gestor
                            if (calls.incrementAndGet() == 1) {
                                br.configure(Bridge.Mode.PROXY, new Upstream("socks5", "127.0.0.1", socksPort, "", ""), false, "");
                            }
                        }
                    });
                    Object[] r = bridgeConnect(port, "ejemplo.test:443");
                    eq("HTTP/1.1 200 Connection Established", r[0], "la conexión nueva se atiende con la ruta nueva");
                    eq("hola", echoThrough((Socket) r[1], "hola"), "y funciona");
                    ((Socket) r[1]).close();
                    eq("3:ejemplo.test:443", socks.requests.get(0), "salió por el proxy");
                    ok(calls.get() >= 1, "el gancho se llamó");
                } finally {
                    br.stop();
                    socks.close();
                    echo.close();
                }
            }
        });
        test("Puente: modo directo sólo cuando se pide", new Body() {
            public void run() throws Exception {
                EchoServer echo = new EchoServer();
                Bridge br = newBridge();
                try {
                    int port = br.start();
                    br.configure(Bridge.Mode.DIRECT, null, false, "");
                    Object[] r = bridgeConnect(port, "127.0.0.1:" + echo.port());
                    eq("HTTP/1.1 200 Connection Established", r[0], "directo");
                    eq("directo", echoThrough((Socket) r[1], "directo"), "datos");
                    ((Socket) r[1]).close();
                } finally {
                    br.stop();
                    echo.close();
                }
            }
        });
        test("Puente: destinos locales bloqueados por omisión", new Body() {
            public void run() throws Exception {
                EchoServer echo = new EchoServer();
                FakeSocks5 socks = new FakeSocks5(null, null, echo.port());
                Bridge br = new Bridge(); // con el bloqueo local activo
                try {
                    int port = br.start();
                    br.configure(Bridge.Mode.PROXY, new Upstream("socks5", "127.0.0.1", socks.port(), "", ""), false, "");
                    String[] targets = {"127.0.0.1:" + echo.port(), "localhost:80", "192.168.1.1:80", "[::1]:80", "10.0.0.5:22", "router.local:80"};
                    for (String t : targets) {
                        String res = bridgeRequest(port, "CONNECT " + t + " HTTP/1.1\r\nHost: " + t + "\r\n\r\n");
                        ok(res.startsWith("HTTP/1.1 403") && res.contains("local"), "debe bloquear " + t + ": " + res);
                    }
                    String plain = bridgeRequest(port, "GET http://192.168.1.1/ HTTP/1.1\r\nHost: 192.168.1.1\r\n\r\n");
                    ok(plain.startsWith("HTTP/1.1 403"), "también http");
                    eq(0, socks.requests.size(), "el proxy no vio ningún destino local");
                    // En modo directo también se comprueba la dirección ya resuelta
                    br.configure(Bridge.Mode.DIRECT, null, false, "");
                    String direct = bridgeRequest(port, "CONNECT localhost:" + echo.port() + " HTTP/1.1\r\nHost: x\r\n\r\n");
                    ok(direct.startsWith("HTTP/1.1 403") || direct.startsWith("HTTP/1.1 502"), "directo a localhost bloqueado: " + direct);
                    eq(0, echo.connections.get(), "no se llegó a conectar");
                } finally {
                    br.stop();
                    socks.close();
                    echo.close();
                }
            }
        });
        test("Puente: rastreadores bloqueados antes de llegar al proxy", new Body() {
            public void run() throws Exception {
                EchoServer echo = new EchoServer();
                FakeSocks5 socks = new FakeSocks5(null, null, echo.port());
                Bridge br = newBridge();
                try {
                    int port = br.start();
                    br.configure(Bridge.Mode.PROXY, new Upstream("socks5", "127.0.0.1", socks.port(), "", ""), true, "");
                    String res = bridgeRequest(port, "CONNECT www.google-analytics.com:443 HTTP/1.1\r\nHost: x\r\n\r\n");
                    ok(res.startsWith("HTTP/1.1 403") && res.contains("Rastreador"), "bloqueado: " + res);
                    eq(0, socks.requests.size(), "no llegó al proxy");
                    eq(1L, br.trackersBlocked(), "contador");
                    br.configure(Bridge.Mode.PROXY, new Upstream("socks5", "127.0.0.1", socks.port(), "", ""), false, "");
                    Object[] r = bridgeConnect(port, "www.google-analytics.com:443");
                    ok(((String) r[0]).contains("200"), "con el bloqueo apagado pasa");
                    ((Socket) r[1]).close();
                } finally {
                    br.stop();
                    socks.close();
                    echo.close();
                }
            }
        });
        test("Puente: petición http absoluta por SOCKS5 (reescribe a forma de origen)", new Body() {
            public void run() throws Exception {
                OriginServer web = new OriginServer("{\"ok\":true}", null);
                FakeSocks5 socks = new FakeSocks5("u", "p", web.port());
                Bridge br = newBridge();
                try {
                    int port = br.start();
                    br.configure(Bridge.Mode.PROXY, new Upstream("socks5", "127.0.0.1", socks.port(), "u", "p"), false, "");
                    String res = bridgeRequest(port, "GET http://sitio.test/ruta/a?x=1#frag HTTP/1.1\r\nHost: sitio.test\r\nProxy-Connection: keep-alive\r\n"
                            + "Proxy-Authorization: Basic c2VjcmV0bw==\r\nConnection: keep-alive\r\nKeep-Alive: 300\r\nAccept: */*\r\n\r\n");
                    ok(res.startsWith("HTTP/1.1 200") && res.endsWith("{\"ok\":true}"), "respuesta del origen: " + res);
                    String seen = web.heads.get(0);
                    ok(seen.startsWith("GET /ruta/a?x=1 HTTP/1.1\r\n"), "forma de origen sin fragmento: " + seen);
                    ok(seen.contains("Host: sitio.test") && seen.contains("Accept: */*"), "conserva cabeceras");
                    ok(!seen.toLowerCase().contains("proxy-") && !seen.toLowerCase().contains("keep-alive"), "quita cabeceras de proxy: " + seen);
                    ok(seen.contains("Connection: close"), "Connection: close");
                    eq("3:sitio.test:80", socks.requests.get(0), "dominio al proxy");
                } finally {
                    br.stop();
                    socks.close();
                    web.close();
                }
            }
        });
        test("Puente: petición http absoluta por proxy HTTP (con usuario) y por CONNECT", new Body() {
            public void run() throws Exception {
                EchoServer echo = new EchoServer();
                FakeHttpProxy px = new FakeHttpProxy("bob", "clave", echo.port(), null);
                Bridge br = newBridge();
                try {
                    int port = br.start();
                    br.configure(Bridge.Mode.PROXY, new Upstream("http", "127.0.0.1", px.port(), "bob", "clave"), false, "");
                    String res = bridgeRequest(port, "GET http://sitio.test:8080/p?q=1 HTTP/1.1\r\nHost: sitio.test:8080\r\nProxy-Connection: keep-alive\r\n\r\n");
                    ok(res.contains("PROXY-SAW:GET http://sitio.test:8080/p?q=1 HTTP/1.1"), "forma absoluta hacia el proxy: " + res);
                    ok(res.contains("Proxy-Authorization: Basic " + Base64.getEncoder().encodeToString("bob:clave".getBytes(StandardCharsets.UTF_8))), "credenciales: " + res);
                    ok(!res.contains("Proxy-Connection"), "sin Proxy-Connection del navegador");
                    Object[] r = bridgeConnect(port, "seguro.test:443");
                    eq("HTTP/1.1 200 Connection Established", r[0], "CONNECT");
                    eq("zzz", echoThrough((Socket) r[1], "zzz"), "datos");
                    ((Socket) r[1]).close();
                    ok(px.heads.get(1).startsWith("CONNECT seguro.test:443 HTTP/1.1"), "el proxy recibió CONNECT con el nombre");
                    px.user = "otro"; // credenciales distintas: el proxy rechaza
                    Object[] bad = bridgeConnect(port, "seguro.test:443");
                    ok(((String) bad[0]).contains("502"), "407 del proxy se convierte en 502: " + bad[0]);
                    ok(br.lastError().contains("usuario o la contraseña"), "mensaje: " + br.lastError());
                } finally {
                    br.stop();
                    px.close();
                    echo.close();
                }
            }
        });
        test("Puente: peticiones inválidas", new Body() {
            public void run() throws Exception {
                Bridge br = newBridge();
                try {
                    int port = br.start();
                    br.configure(Bridge.Mode.DIRECT, null, false, "");
                    ok(bridgeRequest(port, "GET / HTTP/1.1\r\nHost: x\r\n\r\n").startsWith("HTTP/1.1 400"), "forma de origen: es un puente, no un sitio");
                    ok(bridgeRequest(port, "basura sin forma\r\n\r\n").startsWith("HTTP/1.1 400"), "basura");
                    ok(bridgeRequest(port, "CONNECT sin-puerto-ni-nada: HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 400"), "destino roto");
                    ok(bridgeRequest(port, "CONNECT a@b.com:443 HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 400"), "con arroba");
                    StringBuilder big = new StringBuilder("GET http://x.test/ HTTP/1.1\r\n");
                    for (int i = 0; i < 6000; i++) big.append("X-Relleno-").append(i).append(": aaaaaaaaaaaa\r\n");
                    String huge = bridgeRequest(port, big.toString() + "\r\n");
                    ok(huge.isEmpty() || huge.startsWith("HTTP/1.1 431"), "cabecera enorme: se rechaza (" + huge.length() + " bytes de respuesta)");
                    Socket s = new Socket("127.0.0.1", port);
                    s.close(); // se conecta y se va sin decir nada: no debe romper nada
                    Thread.sleep(100);
                    ok(bridgeRequest(port, "CONNECT x.test:443 HTTP/1.1\r\n\r\n") != null, "sigue vivo");
                } finally {
                    br.stop();
                }
            }
        });
        test("Puente: muchas conexiones a la vez y datos grandes íntegros", new Body() {
            public void run() throws Exception {
                final EchoServer echo = new EchoServer();
                FakeSocks5 socks = new FakeSocks5(null, null, echo.port());
                final Bridge br = newBridge();
                try {
                    final int port = br.start();
                    br.configure(Bridge.Mode.PROXY, new Upstream("socks5", "127.0.0.1", socks.port(), "", ""), false, "");
                    final int n = 30;
                    final List<Throwable> errors = Collections.synchronizedList(new ArrayList<Throwable>());
                    List<Thread> ts = new ArrayList<Thread>();
                    for (int i = 0; i < n; i++) {
                        final int id = i;
                        Thread t = new Thread(new Runnable() {
                            public void run() {
                                try {
                                    Object[] r = bridgeConnect(port, "dest" + id + ".test:443");
                                    Socket s = (Socket) r[1];
                                    String msg = "mensaje-" + id + "-" + new Random(id).nextInt();
                                    String back = echoThrough(s, msg);
                                    if (!msg.equals(back)) throw new AssertionError("eco distinto: " + back);
                                    s.close();
                                } catch (Throwable e) {
                                    errors.add(e);
                                }
                            }
                        });
                        ts.add(t);
                        t.start();
                    }
                    for (Thread t : ts) t.join(20000);
                    ok(errors.isEmpty(), "sin errores: " + errors);
                    eq(n, socks.requests.size(), "todas llegaron al proxy");

                    // 3 MB con suma de verificación
                    Object[] r = bridgeConnect(port, "grande.test:443");
                    final Socket s = (Socket) r[1];
                    final byte[] data = new byte[3 * 1024 * 1024];
                    new Random(7).nextBytes(data);
                    Thread writer = new Thread(new Runnable() {
                        public void run() {
                            try {
                                s.getOutputStream().write(data);
                                s.getOutputStream().flush();
                            } catch (IOException e) {
                                errors.add(e);
                            }
                        }
                    });
                    writer.start();
                    byte[] back = new byte[data.length];
                    new DataInputStream(s.getInputStream()).readFully(back);
                    writer.join();
                    ok(Arrays.equals(data, back), "los 3 MB llegaron idénticos");
                    s.close();
                } finally {
                    br.stop();
                    socks.close();
                    echo.close();
                }
            }
        });
        test("Puente: cierre a medias (el cliente termina de enviar y aún lee)", new Body() {
            public void run() throws Exception {
                EchoServer echo = new EchoServer();
                FakeSocks5 socks = new FakeSocks5(null, null, echo.port());
                Bridge br = newBridge();
                try {
                    int port = br.start();
                    br.configure(Bridge.Mode.PROXY, new Upstream("socks5", "127.0.0.1", socks.port(), "", ""), false, "");
                    Object[] r = bridgeConnect(port, "x.test:443");
                    Socket s = (Socket) r[1];
                    s.getOutputStream().write("fin de envío".getBytes(StandardCharsets.UTF_8));
                    s.shutdownOutput();
                    eq("fin de envío", text(readAll(s, 5000)), "recibe todo y luego EOF");
                    s.close();
                } finally {
                    br.stop();
                    socks.close();
                    echo.close();
                }
            }
        });
        test("Puente: stop() corta todo y deja de escuchar", new Body() {
            public void run() throws Exception {
                EchoServer echo = new EchoServer();
                FakeSocks5 socks = new FakeSocks5(null, null, echo.port());
                Bridge br = newBridge();
                int port = br.start();
                br.configure(Bridge.Mode.PROXY, new Upstream("socks5", "127.0.0.1", socks.port(), "", ""), false, "");
                Object[] r = bridgeConnect(port, "x.test:443");
                Socket s = (Socket) r[1];
                br.stop();
                s.setSoTimeout(3000);
                int n;
                try {
                    n = s.getInputStream().read();
                } catch (IOException e) {
                    n = -1;
                }
                eq(-1, n, "el túnel se cortó");
                try {
                    new Socket("127.0.0.1", port).close();
                    throw new AssertionError("ya no debería aceptar conexiones");
                } catch (IOException expected) {
                    // bien
                } finally {
                    socks.close();
                    echo.close();
                }
            }
        });
    }

    // ------------------------------------------------------------------ ProxyTester

    static void testerTests() {
        test("ProxyTester: interpretar respuestas de servicios de IP", new Body() {
            public void run() {
                ProxyTester.IpInfo a = ProxyTester.parseIpResponse("ipinfo", "{\"ip\":\"203.0.113.7\",\"city\":\"Madrid\",\"region\":\"Madrid\",\"country\":\"es\",\"org\":\"AS1 Ejemplo\"}");
                eq("203.0.113.7", a.ip, "ip");
                eq("ES", a.country, "país en mayúsculas");
                eq("Madrid", a.city, "ciudad");
                eq("AS1 Ejemplo", a.org, "operador");
                eq("198.51.100.2", ProxyTester.parseIpResponse("json", "{\"ip\":\"198.51.100.2\"}").ip, "ipify");
                ProxyTester.IpInfo t = ProxyTester.parseIpResponse("trace", "fl=1\nip=2001:db8::9\nloc=MX\nts=1");
                ok("2001:db8::9".equals(t.ip) && "MX".equals(t.country), "trace");
                eq("192.0.2.55", ProxyTester.parseIpResponse("text", "192.0.2.55\n").ip, "texto plano");
                eq("192.0.2.1", ProxyTester.parseIpResponse("json", "tu ip es 192.0.2.1 ok").ip, "IP dentro de un texto");
                ok(ProxyTester.parseIpResponse("json", "<html>error</html>").ip == null, "basura");
                ok(ProxyTester.parseIpResponse("json", "{\"ip\":\"no-es-ip\"}").ip == null, "ip inválida");
                ok(ProxyTester.parseIpResponse("json", null).ip == null, "null");
            }
        });
        test("ProxyTester: respuestas HTTP (con trozos)", new Body() {
            public void run() {
                String raw = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhola \r\n5\r\nmundo\r\n0\r\n\r\n";
                ProxyTester.HttpResponse r = ProxyTester.parseHttpResponse(raw.getBytes(StandardCharsets.ISO_8859_1));
                eq(200, r.status, "estado");
                eq("hola mundo", r.body, "cuerpo reensamblado");
                ok(ProxyTester.parseHttpResponse("basura".getBytes(StandardCharsets.ISO_8859_1)) == null, "sin cabecera");
                ok(ProxyTester.parseHttpResponse("SIP/2.0 200 OK\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1)) == null, "no es HTTP");
            }
        });
        test("ProxyTester: prueba completa por SOCKS5 + HTTPS", new Body() {
            public void run() throws Exception {
                OriginServer ipSvc = new OriginServer("{\"ip\":\"203.0.113.9\",\"country\":\"NL\",\"city\":\"Ámsterdam\"}", serverTls);
                FakeSocks5 socks = new FakeSocks5("u", "p", ipSvc.port());
                try {
                    ProxyTester.Options o = new ProxyTester.Options();
                    o.endpoints = new ArrayList<ProxyTester.Endpoint>();
                    o.endpoints.add(new ProxyTester.Endpoint("localhost", 443, "/json", "ipinfo", true));
                    o.timeoutMs = 5000;
                    ProxyTester.Result r = ProxyTester.run(new Upstream("socks5", "127.0.0.1", socks.port(), "u", "p"), false, o);
                    ok(r.ok && r.via.ok, "debe funcionar: " + r.via.errorMessage);
                    eq("203.0.113.9", r.via.ip, "IP");
                    eq("NL", r.via.country, "país");
                    ok(r.via.tcpMs >= 0 && r.via.tunnelMs >= 0 && r.via.totalMs >= 0, "tiempos");
                    eq(3, r.via.steps.size(), "tres pasos");
                    ok(ProxyTester.describe(r).contains("✔ El proxy funciona") && ProxyTester.describe(r).contains("203.0.113.9"), "texto");
                    ok(ProxyTester.shortLine(r).startsWith("OK · IP 203.0.113.9 (NL)"), ProxyTester.shortLine(r));
                    eq("3:localhost:443", socks.requests.get(0), "el nombre se resolvió en el proxy");
                } finally {
                    socks.close();
                    ipSvc.close();
                }
            }
        });
        test("ProxyTester: errores se explican y distinguen", new Body() {
            public void run() throws Exception {
                OriginServer ipSvc = new OriginServer("{\"ip\":\"203.0.113.9\"}", null);
                FakeSocks5 socks = new FakeSocks5("u", "p", ipSvc.port());
                try {
                    ProxyTester.Options o = new ProxyTester.Options();
                    o.endpoints = new ArrayList<ProxyTester.Endpoint>();
                    o.endpoints.add(new ProxyTester.Endpoint("127.0.0.1", ipSvc.port(), "/", "json", false));
                    o.timeoutMs = 3000;
                    ProxyTester.Result bad = ProxyTester.run(new Upstream("socks5", "127.0.0.1", socks.port(), "u", "mal"), false, o);
                    ok(!bad.ok && "AUTH_FAILED".equals(bad.via.errorCode), "credenciales: " + bad.via.errorCode);
                    ok(bad.via.steps.get(0).ok && !bad.via.steps.get(1).ok, "llegó al proxy pero falló el túnel");
                    ok(ProxyTester.describe(bad).contains("✖ El proxy NO funciona") && ProxyTester.describe(bad).contains("usuario o la contraseña"), "texto");

                    ServerSocket closed = loopback();
                    int dead = closed.getLocalPort();
                    closed.close();
                    ProxyTester.Result refused = ProxyTester.run(new Upstream("socks5", "127.0.0.1", dead, "", ""), false, o);
                    eq("REFUSED", refused.via.errorCode, "puerto cerrado");
                    eq(1, refused.via.steps.size(), "se detiene en el primer paso");

                    // Un destino que falla se salta y se usa el siguiente
                    socks.failHosts.add("primero.test");
                    ProxyTester.Options two = new ProxyTester.Options();
                    two.timeoutMs = 3000;
                    two.endpoints = new ArrayList<ProxyTester.Endpoint>();
                    two.endpoints.add(new ProxyTester.Endpoint("primero.test", ipSvc.port(), "/", "json", false));
                    two.endpoints.add(new ProxyTester.Endpoint("segundo.test", 80, "/", "json", false));
                    ProxyTester.Result fb = ProxyTester.run(new Upstream("socks5", "127.0.0.1", socks.port(), "u", "p"), false, two);
                    ok(fb.ok, "usa el segundo servicio: " + fb.via.errorMessage);
                    eq("segundo.test", fb.via.endpoint, "servicio usado");
                } finally {
                    socks.close();
                    ipSvc.close();
                }
            }
        });
        test("ProxyTester: comparar con la IP real", new Body() {
            public void run() throws Exception {
                OriginServer viaProxy = new OriginServer("{\"ip\":\"203.0.113.9\"}", null);
                OriginServer realNet = new OriginServer("{\"ip\":\"198.51.100.4\"}", null);
                FakeSocks5 socks = new FakeSocks5(null, null, viaProxy.port());
                try {
                    ProxyTester.Options o = new ProxyTester.Options();
                    o.timeoutMs = 3000;
                    o.endpoints = new ArrayList<ProxyTester.Endpoint>();
                    o.endpoints.add(new ProxyTester.Endpoint("127.0.0.1", realNet.port(), "/", "json", false));
                    ProxyTester.Result r = ProxyTester.run(new Upstream("socks5", "127.0.0.1", socks.port(), "", ""), true, o);
                    ok(r.ok && r.direct != null && r.direct.ok, "ambas pruebas");
                    eq("203.0.113.9", r.via.ip, "IP por el proxy");
                    eq("198.51.100.4", r.direct.ip, "IP real");
                    eq(Boolean.FALSE, r.sameIp, "distintas");
                    ok(ProxyTester.describe(r).contains("✔ Es distinta"), "texto");
                    socks.mapTo = realNet.port(); // el «proxy» sale por la misma IP que la real
                    ProxyTester.Result same = ProxyTester.run(new Upstream("socks5", "127.0.0.1", socks.port(), "", ""), true, o);
                    eq(Boolean.TRUE, same.sameIp, "iguales");
                    ok(ProxyTester.describe(same).contains("MISMA"), "avisa si coinciden");
                } finally {
                    socks.close();
                    viaProxy.close();
                    realNet.close();
                }
            }
        });
        test("ProxyTester: conexión directa y sin internet", new Body() {
            public void run() throws Exception {
                OriginServer realNet = new OriginServer("{\"ip\":\"198.51.100.4\"}", null);
                try {
                    ProxyTester.Options o = new ProxyTester.Options();
                    o.timeoutMs = 3000;
                    o.endpoints = new ArrayList<ProxyTester.Endpoint>();
                    o.endpoints.add(new ProxyTester.Endpoint("127.0.0.1", realNet.port(), "/", "json", false));
                    ProxyTester.Result r = ProxyTester.run(null, false, o);
                    ok(r.ok && !r.viaProxy && "198.51.100.4".equals(r.via.ip), "directo");
                    ok(ProxyTester.describe(r).contains("sin proxy"), "dice que es sin proxy");
                    ServerSocket closed = loopback();
                    int dead = closed.getLocalPort();
                    closed.close();
                    o.endpoints.clear();
                    o.endpoints.add(new ProxyTester.Endpoint("127.0.0.1", dead, "/", "json", false));
                    ProxyTester.Result no = ProxyTester.run(null, false, o);
                    ok(!no.ok && "NO_INTERNET".equals(no.via.errorCode), "sin internet: " + no.via.errorCode);
                } finally {
                    realNet.close();
                }
            }
        });
    }
}
