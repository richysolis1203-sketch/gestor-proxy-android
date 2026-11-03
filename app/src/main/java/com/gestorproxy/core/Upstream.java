package com.gestorproxy.core;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * El proxy «de verdad» al que se conecta una sesión (HTTP, HTTPS o SOCKS5, con o sin usuario).
 * El nombre del destino se envía SIEMPRE como nombre al proxy: lo resuelve el proxy, no tu teléfono.
 */
public final class Upstream {
    public static final String HTTP = "http";
    public static final String HTTPS = "https";
    public static final String SOCKS5 = "socks5";

    public final String protocol;
    public final String host;
    public final int port;
    public final String username;
    public final String password;

    public Upstream(String protocol, String host, int port, String username, String password) {
        this.protocol = protocol;
        this.host = host;
        this.port = port;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
    }

    public boolean hasAuth() {
        return !username.isEmpty();
    }

    public boolean isHttpFamily() {
        return HTTP.equals(protocol) || HTTPS.equals(protocol);
    }

    public String describe() {
        return protocol + "://" + (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
    }

    /** «Proxy-Authorization: Basic ...\r\n» o cadena vacía. */
    String proxyAuthorizationHeader() {
        if (!hasAuth()) return "";
        byte[] raw = (username + ":" + password).getBytes(StandardCharsets.UTF_8);
        return "Proxy-Authorization: Basic " + Base64.getEncoder().encodeToString(raw) + "\r\n";
    }

    // ------------------------------------------------------------------ conexión con el propio proxy

    /** Abre la conexión con el propio proxy (TCP, o TLS si es de tipo HTTPS). */
    public Socket openProxySocket(int timeoutMs) throws IOException {
        Socket s = new Socket();
        try {
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            if (HTTPS.equals(protocol)) {
                Socket t = Tls.wrap(s, host, port);
                t.setSoTimeout(timeoutMs);
                return t;
            }
            return s;
        } catch (IOException e) {
            AddrUtil.closeQuietly(s);
            throw ProxyException.from(e, true);
        } catch (RuntimeException e) {
            AddrUtil.closeQuietly(s);
            throw ProxyException.from(e, true);
        }
    }

    /** Abre un túnel TCP hacia destino:puerto a través del proxy. Al volver, el socket no tiene límite de espera. */
    public Socket connect(String targetHost, int targetPort, int timeoutMs) throws IOException {
        Socket s = openProxySocket(timeoutMs);
        try {
            if (SOCKS5.equals(protocol)) socks5Handshake(s, targetHost, targetPort);
            else httpConnect(s, targetHost, targetPort);
            s.setSoTimeout(0);
            return s;
        } catch (IOException e) {
            AddrUtil.closeQuietly(s);
            throw ProxyException.from(e, true);
        } catch (RuntimeException e) {
            AddrUtil.closeQuietly(s);
            throw ProxyException.from(e, true);
        }
    }

    // ------------------------------------------------------------------ SOCKS5 (RFC 1928 y 1929)

    private static final String[] REPLY_TEXT = {
        "",
        "El proxy tuvo un fallo general.",
        "El proxy no permite esa conexión (reglas del proxy).",
        "El proxy no tiene ruta hacia esa red.",
        "El proxy no pudo encontrar ese destino.",
        "El destino rechazó la conexión.",
        "La conexión caducó dentro del proxy.",
        "El proxy no admite esa operación.",
        "El proxy no admite ese tipo de dirección.",
    };

    static String explainReply(int rep) {
        if (rep >= 1 && rep < REPLY_TEXT.length) return REPLY_TEXT[rep];
        return "El proxy respondió con un error (código " + rep + ").";
    }

    static byte[] buildConnectRequest(String host, int port) throws ProxyException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(5);
        b.write(1);
        b.write(0);
        if (Validate.isIPv4(host) || Validate.isIPv6(host)) {
            byte[] addr;
            try {
                addr = InetAddress.getByName(host).getAddress(); // es un literal: no consulta DNS
            } catch (IOException e) {
                throw new ProxyException("BAD_TARGET", "El destino no es válido.");
            }
            b.write(addr.length == 4 ? 1 : 4);
            b.write(addr, 0, addr.length);
        } else {
            byte[] d = host.getBytes(StandardCharsets.UTF_8);
            if (d.length == 0 || d.length > 255) throw new ProxyException("BAD_TARGET", "El nombre del destino no es válido.");
            b.write(3);
            b.write(d.length);
            b.write(d, 0, d.length);
        }
        b.write((port >> 8) & 0xff);
        b.write(port & 0xff);
        return b.toByteArray();
    }

    private void socks5Handshake(Socket s, String targetHost, int targetPort) throws IOException {
        OutputStream out = s.getOutputStream();
        DataInputStream in = new DataInputStream(s.getInputStream());
        boolean creds = hasAuth();
        out.write(creds ? new byte[] {5, 2, 0, 2} : new byte[] {5, 1, 0});
        out.flush();
        int ver = in.read();
        int method = in.read();
        if (ver < 0 || method < 0) throw new ProxyException("CLOSED", ProxyException.CLOSED_MSG);
        if (ver != 5) {
            throw new ProxyException("BAD_PROTOCOL", "Ese servidor no habla SOCKS5. Prueba con el tipo HTTP o HTTPS.");
        }
        if (method == 0xff) {
            throw new ProxyException(
                    creds ? "AUTH_FAILED" : "AUTH_REQUIRED",
                    creds ? "El proxy no aceptó el método de autenticación." : "El proxy exige usuario y contraseña.");
        }
        if (method == 2) {
            if (!creds) throw new ProxyException("AUTH_REQUIRED", "El proxy exige usuario y contraseña.");
            byte[] u = username.getBytes(StandardCharsets.UTF_8);
            byte[] p = password.getBytes(StandardCharsets.UTF_8);
            if (u.length > 255 || p.length > 255) {
                throw new ProxyException("AUTH_FAILED", "El usuario o la contraseña son demasiado largos.");
            }
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            b.write(1);
            b.write(u.length);
            b.write(u, 0, u.length);
            b.write(p.length);
            b.write(p, 0, p.length);
            out.write(b.toByteArray());
            out.flush();
            int av = in.read();
            int st = in.read();
            if (av < 0 || st < 0) throw new ProxyException("CLOSED", ProxyException.CLOSED_MSG);
            if (st != 0) throw new ProxyException("AUTH_FAILED", "El proxy rechazó el usuario o la contraseña.");
        } else if (method != 0) {
            throw new ProxyException("BAD_PROTOCOL", "El proxy pidió un método de autenticación no soportado (" + method + ").");
        }
        out.write(buildConnectRequest(targetHost, targetPort));
        out.flush();
        byte[] head = new byte[4];
        in.readFully(head);
        if ((head[0] & 0xff) != 5) throw new ProxyException("BAD_PROTOCOL", "Respuesta inválida del proxy SOCKS5.");
        int rep = head[1] & 0xff;
        if (rep != 0) throw new ProxyException("REPLY_" + rep, explainReply(rep));
        int atyp = head[3] & 0xff;
        int addrLen;
        if (atyp == 1) addrLen = 4;
        else if (atyp == 4) addrLen = 16;
        else if (atyp == 3) {
            int l = in.read();
            if (l < 0) throw new ProxyException("CLOSED", ProxyException.CLOSED_MSG);
            addrLen = l;
        } else {
            throw new ProxyException("BAD_PROTOCOL", "Respuesta inválida del proxy SOCKS5.");
        }
        byte[] skip = new byte[addrLen + 2];
        in.readFully(skip);
    }

    // ------------------------------------------------------------------ HTTP CONNECT

    /** Lee la cabecera de una respuesta HTTP byte a byte (sin pasarse) y devuelve el código de estado. */
    static int readStatusLine(InputStream in) throws IOException {
        byte[] buf = new byte[16384];
        int len = 0;
        while (true) {
            int b = in.read();
            if (b < 0) throw new ProxyException("CLOSED", ProxyException.CLOSED_MSG);
            if (len == buf.length) throw new ProxyException("BAD_PROTOCOL", "Respuesta inválida del proxy.");
            buf[len++] = (byte) b;
            if (len >= 5 && len < 9) {
                String start = new String(buf, 0, 5, StandardCharsets.ISO_8859_1);
                if (!start.equals("HTTP/")) {
                    throw new ProxyException("BAD_PROTOCOL", "Ese servidor no responde como un proxy HTTP. ¿Es un proxy SOCKS5?");
                }
            }
            if (len >= 4 && buf[len - 4] == '\r' && buf[len - 3] == '\n' && buf[len - 2] == '\r' && buf[len - 1] == '\n') break;
        }
        String head = new String(buf, 0, len, StandardCharsets.ISO_8859_1);
        int sp = head.indexOf(' ');
        if (!head.startsWith("HTTP/1.") || sp < 0 || head.length() < sp + 4) {
            throw new ProxyException("BAD_PROTOCOL", "Respuesta inválida del proxy.");
        }
        try {
            return Integer.parseInt(head.substring(sp + 1, sp + 4));
        } catch (NumberFormatException e) {
            throw new ProxyException("BAD_PROTOCOL", "Respuesta inválida del proxy.");
        }
    }

    private void httpConnect(Socket s, String targetHost, int targetPort) throws IOException {
        String target = (targetHost.indexOf(':') >= 0 ? "[" + targetHost + "]" : targetHost) + ":" + targetPort;
        String req = "CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\n" + proxyAuthorizationHeader()
                + "Proxy-Connection: keep-alive\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(req.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        int status = readStatusLine(s.getInputStream());
        if (status >= 200 && status < 300) return;
        if (status == 407) {
            throw new ProxyException(
                    hasAuth() ? "AUTH_FAILED" : "AUTH_REQUIRED",
                    hasAuth() ? "El proxy rechazó el usuario o la contraseña." : "El proxy exige usuario y contraseña.");
        }
        if (status == 403) throw new ProxyException("FORBIDDEN", "El proxy no permite conectarse a este destino.");
        if (status == 502 || status == 503 || status == 504) {
            throw new ProxyException("BAD_GATEWAY", "El proxy no pudo conectarse al destino.");
        }
        throw new ProxyException("HTTP_" + status, "El proxy respondió con el código " + status + ".");
    }
}
