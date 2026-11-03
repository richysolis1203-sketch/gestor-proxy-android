package com.gestorproxy.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prueba de conexión: comprueba que el proxy funciona, obtiene la IP pública que verían los sitios web y mide la
 * latencia. Distingue «no llego al proxy» de «el proxy rechaza mis credenciales» o «el destino falla».
 * Todo el tráfico de la prueba pasa por el proxy (o sale directo cuando proxy == null).
 */
public final class ProxyTester {
    private ProxyTester() {}

    public static final String DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";

    private static final Set<String> TARGET_SIDE =
            new HashSet<String>(Arrays.asList("FORBIDDEN", "BAD_GATEWAY", "REPLY_2", "REPLY_3", "REPLY_4", "REPLY_5", "REPLY_6", "TARGET"));

    public static final class Endpoint {
        public final String host;
        public final int port;
        public final String path;
        public final String parser; // ipinfo | json | trace | text
        public final boolean tls;

        public Endpoint(String host, int port, String path, String parser, boolean tls) {
            this.host = host;
            this.port = port;
            this.path = path;
            this.parser = parser;
            this.tls = tls;
        }
    }

    public static List<Endpoint> defaultEndpoints() {
        List<Endpoint> l = new ArrayList<Endpoint>();
        l.add(new Endpoint("ipinfo.io", 443, "/json", "ipinfo", true));
        l.add(new Endpoint("api.ipify.org", 443, "/?format=json", "json", true));
        l.add(new Endpoint("www.cloudflare.com", 443, "/cdn-cgi/trace", "trace", true));
        l.add(new Endpoint("ifconfig.me", 443, "/ip", "text", true));
        return l;
    }

    public static final class Options {
        public List<Endpoint> endpoints = defaultEndpoints();
        public int timeoutMs = 12000;
        public String userAgent = DEFAULT_UA;
    }

    public static final class Step {
        public final String id;
        public final String label;
        public final boolean ok;
        public final long ms;
        public final String message;

        Step(String id, String label, boolean ok, long ms, String message) {
            this.id = id;
            this.label = label;
            this.ok = ok;
            this.ms = ms;
            this.message = message;
        }
    }

    public static final class IpInfo {
        public String ip;
        public String country;
        public String city;
        public String region;
        public String org;
    }

    public static final class Leg {
        public boolean ok;
        public String ip;
        public String country;
        public String city;
        public String region;
        public String org;
        public String endpoint;
        public long tcpMs = -1;
        public long tunnelMs = -1;
        public long totalMs = -1;
        public String errorCode;
        public String errorMessage;
        public final List<Step> steps = new ArrayList<Step>();
    }

    public static final class Result {
        public boolean ok;
        public long at;
        public Leg via;
        public Leg direct; // sólo si se pidió comparar
        public Boolean sameIp; // null si no se pudo comparar
        public boolean viaProxy;
    }

    private static long ms(long t0) {
        return Math.max(0L, (System.nanoTime() - t0) / 1000000L);
    }

    // ------------------------------------------------------------------ HTTP sobre el túnel

    static final class HttpResponse {
        int status;
        String body;
    }

    static byte[] dechunk(byte[] buf) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int pos = 0;
        while (pos < buf.length) {
            int eol = -1;
            for (int i = pos; i + 1 < buf.length; i++) {
                if (buf[i] == '\r' && buf[i + 1] == '\n') {
                    eol = i;
                    break;
                }
            }
            if (eol < 0) break;
            String sizeText = new String(buf, pos, eol - pos, StandardCharsets.ISO_8859_1);
            int semi = sizeText.indexOf(';');
            if (semi >= 0) sizeText = sizeText.substring(0, semi);
            int size;
            try {
                size = Integer.parseInt(sizeText.trim(), 16);
            } catch (NumberFormatException e) {
                break;
            }
            if (size <= 0) break;
            pos = eol + 2;
            int take = Math.min(size, buf.length - pos);
            if (take <= 0) break;
            out.write(buf, pos, take);
            pos += size + 2;
        }
        return out.toByteArray();
    }

    static HttpResponse parseHttpResponse(byte[] raw) {
        int sep = -1;
        for (int i = 0; i + 3 < raw.length; i++) {
            if (raw[i] == '\r' && raw[i + 1] == '\n' && raw[i + 2] == '\r' && raw[i + 3] == '\n') {
                sep = i;
                break;
            }
        }
        if (sep < 0) return null;
        String[] lines = new String(raw, 0, sep, StandardCharsets.ISO_8859_1).split("\r\n");
        Matcher m = Pattern.compile("^HTTP/1\\.[01] (\\d{3})").matcher(lines[0]);
        if (!m.find()) return null;
        boolean chunked = false;
        for (int i = 1; i < lines.length; i++) {
            String l = lines[i].toLowerCase(Locale.ROOT);
            if (l.startsWith("transfer-encoding:") && l.contains("chunked")) chunked = true;
        }
        byte[] body = Arrays.copyOfRange(raw, sep + 4, raw.length);
        if (chunked) body = dechunk(body);
        HttpResponse r = new HttpResponse();
        r.status = Integer.parseInt(m.group(1));
        r.body = new String(body, StandardCharsets.UTF_8);
        return r;
    }

    private static byte[] httpGet(Socket tunnel, Endpoint ep, int timeoutMs, String ua) throws IOException {
        Socket s = tunnel;
        if (ep.tls) s = Tls.wrap(tunnel, ep.host, ep.port);
        try {
            s.setSoTimeout(timeoutMs);
            boolean defaultPort = (ep.tls && ep.port == 443) || (!ep.tls && ep.port == 80);
            String hostHeader = defaultPort ? ep.host : ep.host + ":" + ep.port;
            String req = "GET " + ep.path + " HTTP/1.1\r\nHost: " + hostHeader + "\r\nUser-Agent: " + ua
                    + "\r\nAccept: application/json, text/plain, */*\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n";
            OutputStream out = s.getOutputStream();
            out.write(req.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            long deadline = System.nanoTime() + timeoutMs * 1000000L;
            while (true) {
                if (System.nanoTime() > deadline) throw new java.net.SocketTimeoutException("timeout");
                int n;
                try {
                    n = in.read(chunk);
                } catch (javax.net.ssl.SSLException e) {
                    // Algunos servidores cierran sin enviar «close_notify»: si ya hay datos, se acepta.
                    if (buf.size() > 0) break;
                    throw e;
                }
                if (n < 0) break;
                buf.write(chunk, 0, n);
                if (buf.size() > 262144) throw new IOException("La respuesta del servicio de IP es demasiado grande.");
            }
            return buf.toByteArray();
        } finally {
            AddrUtil.closeQuietly(s);
        }
    }

    // ------------------------------------------------------------------ interpretar la respuesta

    private static String clean(Object v) {
        if (!(v instanceof String)) return null;
        String t = ((String) v).trim();
        if (t.isEmpty()) return null;
        return t.length() > 80 ? t.substring(0, 80) : t;
    }

    static boolean isIp(String s) {
        if (s == null) return false;
        String t = s.trim();
        return Validate.isIPv4(t) || Validate.isIPv6(t);
    }

    private static String country2(String c) {
        return c != null && c.matches("^[A-Za-z]{2}$") ? c.toUpperCase(Locale.ROOT) : null;
    }

    static IpInfo parseIpResponse(String parser, String bodyText) {
        String text = bodyText == null ? "" : bodyText.trim();
        IpInfo info = new IpInfo();
        if ("trace".equals(parser)) {
            Map<String, String> map = new LinkedHashMap<String, String>();
            for (String line : text.split("\n")) {
                int i = line.indexOf('=');
                if (i > 0) map.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
            }
            if (isIp(map.get("ip"))) {
                info.ip = map.get("ip");
                info.country = country2(map.get("loc"));
                return info;
            }
        }
        try {
            Map<String, Object> j = Json.obj(Json.parse(text));
            if (j != null) {
                String ip = null;
                for (String k : new String[] {"ip", "query", "origin"}) {
                    Object v = j.get(k);
                    if (v instanceof String && isIp((String) v)) {
                        ip = ((String) v).trim();
                        break;
                    }
                }
                if (ip != null) {
                    info.ip = ip;
                    String c = clean(j.get("country"));
                    if (c == null) c = clean(j.get("country_code"));
                    if (c == null) c = clean(j.get("countryCode"));
                    info.country = country2(c);
                    info.city = clean(j.get("city"));
                    info.region = clean(j.get("region"));
                    if (info.region == null) info.region = clean(j.get("regionName"));
                    info.org = clean(j.get("org"));
                    if (info.org == null) info.org = clean(j.get("isp"));
                    return info;
                }
            }
        } catch (Json.JsonException ignored) {
            // no era JSON: se busca una IP en el texto
        }
        Matcher m = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b|\\b[0-9a-fA-F]{0,4}(?::[0-9a-fA-F]{0,4}){2,7}\\b").matcher(text);
        if (m.find() && isIp(m.group())) info.ip = m.group();
        return info;
    }

    // ------------------------------------------------------------------ pruebas

    private static Socket openDirect(String host, int port, int timeoutMs) throws IOException {
        Socket s = new Socket();
        try {
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return s;
        } catch (IOException e) {
            AddrUtil.closeQuietly(s);
            throw e;
        }
    }

    /** Una consulta de IP. {@code proxy} == null significa conexión directa. */
    public static Leg runLeg(Upstream proxy, Options opts) {
        Options o = opts == null ? new Options() : opts;
        long t0 = System.nanoTime();
        Leg leg = new Leg();
        boolean direct = proxy == null;
        String ipLabel = direct ? "Consultar tu IP pública (sin proxy)" : "Consultar tu IP pública";
        String tunnelLabel = direct ? "Conectar con el servicio de IP" : "Abrir un túnel a través del proxy";
        try {
            if (!direct) {
                long t = System.nanoTime();
                try {
                    Socket s = proxy.openProxySocket(o.timeoutMs);
                    AddrUtil.closeQuietly(s);
                    leg.tcpMs = ms(t);
                    leg.steps.add(new Step("tcp", "Llegar al proxy", true, leg.tcpMs, "Conexión establecida en " + leg.tcpMs + " ms."));
                } catch (IOException e) {
                    ProxyException pe = ProxyException.from(e, true);
                    leg.steps.add(new Step("tcp", "Llegar al proxy", false, -1, pe.getMessage()));
                    leg.errorCode = pe.code;
                    leg.errorMessage = pe.getMessage();
                    return leg;
                }
            }

            ProxyException lastError = null;
            Step tunnelStep = null;
            Step ipStep = null;
            for (Endpoint ep : o.endpoints) {
                leg.endpoint = ep.host;
                long tTunnel = System.nanoTime();
                Socket tunnel;
                try {
                    tunnel = direct ? openDirect(ep.host, ep.port, o.timeoutMs) : proxy.connect(ep.host, ep.port, o.timeoutMs);
                } catch (IOException e) {
                    ProxyException pe = direct
                            ? new ProxyException("NO_INTERNET", "No se pudo conectar. Revisa tu conexión a internet.", e)
                            : ProxyException.from(e, true);
                    lastError = pe;
                    tunnelStep = new Step("tunnel", tunnelLabel, false, -1, pe.getMessage());
                    if (!direct && !TARGET_SIDE.contains(pe.code)) {
                        leg.steps.add(tunnelStep);
                        leg.errorCode = pe.code;
                        leg.errorMessage = pe.getMessage();
                        return leg;
                    }
                    continue; // falla ese destino: se prueba el siguiente
                }
                leg.tunnelMs = ms(tTunnel);
                tunnelStep = new Step("tunnel", tunnelLabel, true, leg.tunnelMs, "Listo en " + leg.tunnelMs + " ms.");
                try {
                    byte[] raw = httpGet(tunnel, ep, o.timeoutMs, o.userAgent);
                    HttpResponse res = parseHttpResponse(raw);
                    if (res == null) throw new IOException("Respuesta inválida del servicio de IP.");
                    if (res.status < 200 || res.status >= 300) {
                        throw new IOException("El servicio de IP respondió con el código " + res.status + ".");
                    }
                    IpInfo info = parseIpResponse(ep.parser, res.body);
                    if (info.ip == null) throw new IOException("El servicio de IP no devolvió una dirección.");
                    leg.ip = info.ip;
                    leg.country = info.country;
                    leg.city = info.city;
                    leg.region = info.region;
                    leg.org = info.org;
                    leg.ok = true;
                    ipStep = new Step("ip", ipLabel, true, ms(tTunnel), "IP detectada: " + info.ip);
                    lastError = null;
                    break;
                } catch (IOException e) {
                    lastError = e instanceof ProxyException ? (ProxyException) e : new ProxyException("TARGET", explainTarget(e), e);
                    ipStep = new Step("ip", ipLabel, false, -1, lastError.getMessage());
                } catch (RuntimeException e) {
                    lastError = new ProxyException("TARGET", explainTarget(e), e);
                    ipStep = new Step("ip", ipLabel, false, -1, lastError.getMessage());
                }
            }
            if (tunnelStep != null) leg.steps.add(tunnelStep);
            if (ipStep != null) leg.steps.add(ipStep);
            if (!leg.ok) {
                if (lastError == null) lastError = new ProxyException("ERROR", "No se pudo obtener la IP pública.");
                leg.errorCode = lastError.code;
                leg.errorMessage = lastError.getMessage();
            }
            return leg;
        } finally {
            leg.totalMs = ms(t0);
        }
    }

    private static String explainTarget(Throwable e) {
        ProxyException pe = ProxyException.from(e, false);
        if ("ERROR".equals(pe.code)) {
            String m = e.getMessage();
            return m == null || m.isEmpty() ? "No se pudo obtener la IP pública." : m;
        }
        return pe.getMessage();
    }

    /**
     * Prueba completa. {@code proxy} puede ser null (sólo conexión directa). Con {@code compareDirect} también se
     * consulta la IP real para avisar si coincide con la del proxy.
     */
    public static Result run(final Upstream proxy, boolean compareDirect, final Options opts) {
        final Leg[] directHolder = new Leg[1];
        Thread t = null;
        if (compareDirect && proxy != null) {
            t = new Thread(new Runnable() {
                @Override
                public void run() {
                    directHolder[0] = runLeg(null, opts);
                }
            }, "gp-test-direct");
            t.setDaemon(true);
            t.start();
        }
        Leg via = runLeg(proxy, opts);
        if (t != null) {
            try {
                t.join(30000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Result r = new Result();
        r.at = System.currentTimeMillis();
        r.via = via;
        r.viaProxy = proxy != null;
        r.direct = directHolder[0];
        r.ok = via.ok;
        r.sameIp = r.direct != null && via.ip != null && r.direct.ip != null ? Boolean.valueOf(via.ip.equals(r.direct.ip)) : null;
        return r;
    }

    // ------------------------------------------------------------------ texto para mostrar

    private static String place(Leg l) {
        List<String> parts = new ArrayList<String>();
        if (l.city != null) parts.add(l.city);
        if (l.region != null && !l.region.equals(l.city)) parts.add(l.region);
        if (l.country != null) parts.add(l.country);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    /** Resumen en español, listo para mostrar en un cuadro de diálogo. */
    public static String describe(Result r) {
        StringBuilder sb = new StringBuilder();
        Leg v = r.via;
        if (v.ok) {
            sb.append(r.viaProxy ? "✔ El proxy funciona." : "✔ Hay conexión (sin proxy).").append('\n').append('\n');
            sb.append(r.viaProxy ? "IP pública que ven los sitios: " : "Tu IP pública (sin proxy): ").append(v.ip);
            String where = place(v);
            if (!where.isEmpty()) sb.append("\nUbicación aproximada: ").append(where);
            if (v.org != null) sb.append("\nOperador: ").append(v.org);
        } else {
            sb.append(r.viaProxy ? "✖ El proxy NO funciona." : "✖ No hay conexión.").append('\n').append('\n');
            sb.append(v.errorMessage == null ? "Error desconocido." : v.errorMessage);
        }
        sb.append("\n\nTiempos:");
        if (v.tcpMs >= 0) sb.append("\n• Llegar al proxy: ").append(v.tcpMs).append(" ms");
        if (v.tunnelMs >= 0) sb.append("\n• Abrir el túnel: ").append(v.tunnelMs).append(" ms");
        sb.append("\n• Total: ").append(v.totalMs).append(" ms");
        if (r.direct != null) {
            sb.append("\n\nComparación con tu conexión normal:");
            if (r.direct.ok) {
                sb.append("\nTu IP real es ").append(r.direct.ip).append('.');
                if (Boolean.TRUE.equals(r.sameIp)) {
                    sb.append("\n⚠ Es la MISMA que la del proxy: el proxy no está ocultando tu IP.");
                } else if (Boolean.FALSE.equals(r.sameIp)) {
                    sb.append("\n✔ Es distinta de la del proxy.");
                }
            } else {
                sb.append("\nNo se pudo consultar tu IP real (").append(r.direct.errorMessage).append(").");
            }
        }
        sb.append("\n\nUna prueba correcta no garantiza anonimato ni protección total: sólo comprueba que el proxy responde.");
        return sb.toString();
    }

    /** Línea corta para las tarjetas («OK · 1.2.3.4 · 320 ms» o el error). */
    public static String shortLine(Result r) {
        Leg v = r.via;
        if (v.ok) return "OK · IP " + v.ip + (v.country != null ? " (" + v.country + ")" : "") + " · " + v.totalMs + " ms";
        return "Falló: " + (v.errorMessage == null ? "error desconocido" : v.errorMessage);
    }
}
