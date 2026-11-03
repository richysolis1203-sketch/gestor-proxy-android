package com.gestorproxy.core;

import java.io.UnsupportedEncodingException;
import java.net.IDN;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validación de todo lo que escribe el usuario. Los mensajes están en español y se muestran tal cual. */
public final class Validate {
    private Validate() {}

    public static final int NAME_MAX = 60;
    public static final int HOST_MAX = 253;
    public static final int CRED_MAX_BYTES = 255;
    public static final int URL_MAX = 2048;
    public static final String SEARCH_URL = "https://duckduckgo.com/?q=";

    private static final SecureRandom RNG = new SecureRandom();
    private static final Pattern PROXY_ID = Pattern.compile("^p-[0-9a-f]{16}$");
    private static final Pattern SESSION_ID = Pattern.compile("^s-[0-9a-f]{16}$");
    private static final Pattern CONTROL = Pattern.compile("[\\u0000-\\u001f\\u007f]");
    private static final Pattern LABEL = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");
    private static final Pattern HEX_GROUP = Pattern.compile("^[0-9a-fA-F]{1,4}$");
    private static final Pattern DOMAIN_LIKE =
            Pattern.compile("^(?:[\\p{L}\\p{N}-]+\\.)+(?:\\p{L}{2,}|xn--[a-z0-9-]+)$");
    private static final Pattern HTTP_SCHEME = Pattern.compile("^https?://", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANY_SCHEME = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.-]*):");
    private static final Pattern HOST_PORT_START = Pattern.compile("^[^\\s/:]+:\\d{1,5}([/?#].*)?$", Pattern.DOTALL);
    private static final Pattern AUTHORITY = Pattern.compile("^([^/?#]+)([/?#].*)?$", Pattern.DOTALL);
    private static final Pattern V6_AUTH = Pattern.compile("^\\[([0-9a-fA-F:.]+)\\](?::(\\d{1,5}))?$");
    private static final Pattern HOST_PORT = Pattern.compile("^([^:]+)(?::(\\d{1,5}))?$");
    private static final Pattern HOST_CHARS = Pattern.compile("^[\\p{L}\\p{N}._-]+$");

    // ------------------------------------------------------------------ identificadores

    public static String newId(String prefix) {
        byte[] b = new byte[8];
        RNG.nextBytes(b);
        StringBuilder sb = new StringBuilder(prefix).append('-');
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    public static boolean isProxyId(String v) {
        return v != null && PROXY_ID.matcher(v).matches();
    }

    public static boolean isSessionId(String v) {
        return v != null && SESSION_ID.matcher(v).matches();
    }

    // ------------------------------------------------------------------ resultados

    /** Un valor ya limpio o un mensaje de error (nunca los dos). */
    public static final class Check {
        public final String value;
        public final String error;

        private Check(String value, String error) {
            this.value = value;
            this.error = error;
        }

        static Check ok(String value) {
            return new Check(value, null);
        }

        static Check fail(String error) {
            return new Check(null, error);
        }

        public boolean ok() {
            return error == null;
        }
    }

    // ------------------------------------------------------------------ nombre, dirección, puerto

    public static Check validateName(String raw, String what) {
        String v = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (v.isEmpty()) return Check.fail("Escribe un nombre para " + what + ".");
        if (CONTROL.matcher(v).find()) return Check.fail("El nombre tiene caracteres no permitidos.");
        if (v.length() > NAME_MAX) return Check.fail("El nombre es muy largo (máximo " + NAME_MAX + " caracteres).");
        return Check.ok(v);
    }

    public static boolean isIPv4(String s) {
        if (s == null) return false;
        String[] p = s.split("\\.", -1);
        if (p.length != 4) return false;
        for (String part : p) {
            if (part.isEmpty() || part.length() > 3) return false;
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c < '0' || c > '9') return false;
            }
            if (part.length() > 1 && part.charAt(0) == '0') return false;
            if (Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    public static boolean isIPv6(String s) {
        if (s == null || s.length() < 2 || s.length() > 45 || s.indexOf(':') < 0) return false;
        String t = s;
        int lastColon = t.lastIndexOf(':');
        String tail = t.substring(lastColon + 1);
        if (tail.indexOf('.') >= 0) {
            if (!isIPv4(tail)) return false;
            t = t.substring(0, lastColon + 1) + "0:0";
        }
        int dc = t.indexOf("::");
        if (dc >= 0 && t.indexOf("::", dc + 1) >= 0) return false;
        if (dc >= 0) {
            String head = t.substring(0, dc);
            String rest = t.substring(dc + 2);
            int count = 0;
            if (!head.isEmpty()) {
                String[] hs = head.split(":", -1);
                for (String g : hs) {
                    if (!HEX_GROUP.matcher(g).matches()) return false;
                }
                count += hs.length;
            }
            if (!rest.isEmpty()) {
                String[] rs = rest.split(":", -1);
                for (String g : rs) {
                    if (!HEX_GROUP.matcher(g).matches()) return false;
                }
                count += rs.length;
            }
            return count <= 7;
        }
        String[] gs = t.split(":", -1);
        if (gs.length != 8) return false;
        for (String g : gs) {
            if (!HEX_GROUP.matcher(g).matches()) return false;
        }
        return true;
    }

    /** Normaliza la dirección de un proxy: dominio, IPv4 o IPv6 (sin corchetes). */
    public static Check normalizeHost(String raw) {
        String h = raw == null ? "" : raw.trim();
        if (h.isEmpty()) {
            return Check.fail("Escribe la dirección del proxy (por ejemplo proxy.ejemplo.com o 192.168.1.10).");
        }
        h = h.replaceFirst("(?i)^[a-z][a-z0-9+.-]*://", "");
        h = h.replaceFirst("(?s)[/?#].*$", "");
        if (h.indexOf('@') >= 0) {
            return Check.fail("Escribe sólo la dirección. El usuario y la contraseña van en sus propios campos.");
        }
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        } else if (h.matches("^[^:]+:\\d+$")) {
            return Check.fail("Escribe el puerto en su propio campo, no junto a la dirección.");
        }
        if (h.isEmpty()) return Check.fail("La dirección no es válida.");
        if (h.length() > HOST_MAX) return Check.fail("La dirección es demasiado larga.");
        if (h.indexOf('%') >= 0) return Check.fail("La dirección no es válida.");
        if (isIPv6(h)) return Check.ok(h.toLowerCase(Locale.ROOT));
        if (isIPv4(h)) return Check.ok(h);
        if (h.matches(".*\\s.*") || CONTROL.matcher(h).find()) return Check.fail("La dirección no puede tener espacios.");

        String ascii;
        try {
            ascii = IDN.toASCII(h, IDN.ALLOW_UNASSIGNED);
        } catch (IllegalArgumentException e) {
            return Check.fail("La dirección no es válida.");
        }
        ascii = ascii.toLowerCase(Locale.ROOT);
        if (ascii.endsWith(".")) ascii = ascii.substring(0, ascii.length() - 1);
        if (ascii.isEmpty() || ascii.length() > HOST_MAX) return Check.fail("La dirección no es válida.");
        String[] labels = ascii.split("\\.", -1);
        for (String l : labels) {
            if (!LABEL.matcher(l).matches()) {
                return Check.fail("La dirección no es válida. Usa letras, números, guiones y puntos.");
            }
        }
        if (labels[labels.length - 1].matches("^\\d+$")) {
            return Check.fail("Esa dirección IP no es válida (por ejemplo 192.168.1.10).");
        }
        return Check.ok(ascii);
    }

    /** El valor es el puerto como texto; use Integer.parseInt(value). */
    public static Check normalizePort(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (!s.matches("^\\d{1,5}$")) return Check.fail("Escribe el puerto como un número entre 1 y 65535.");
        int n = Integer.parseInt(s);
        if (n < 1 || n > 65535) return Check.fail("El puerto debe estar entre 1 y 65535.");
        return Check.ok(String.valueOf(n));
    }

    // ------------------------------------------------------------------ proxy completo

    public static final class ProxyChecked {
        public final Map<String, String> errors = new LinkedHashMap<String, String>();
        public String name = "";
        public String protocol = "";
        public String host = "";
        public int port;
        public String username = "";
        public String password = "";

        public boolean ok() {
            return errors.isEmpty();
        }
    }

    public static boolean isProtocol(String p) {
        return "http".equals(p) || "https".equals(p) || "socks5".equals(p);
    }

    /** Valida lo que el usuario escribió en el formulario de un proxy. */
    public static ProxyChecked validateProxyInput(
            String name, String protocol, String host, String port, String username, String password) {
        ProxyChecked out = new ProxyChecked();

        Check n = validateName(name, "el proxy");
        if (n.ok()) out.name = n.value;
        else out.errors.put("name", n.error);

        String proto = protocol == null ? "" : protocol.trim().toLowerCase(Locale.ROOT);
        if (!isProtocol(proto)) out.errors.put("protocol", "Elige un tipo de proxy: HTTP, HTTPS o SOCKS5.");
        else out.protocol = proto;

        Check h = normalizeHost(host);
        if (h.ok()) out.host = h.value;
        else out.errors.put("host", h.error);

        Check p = normalizePort(port);
        if (p.ok()) out.port = Integer.parseInt(p.value);
        else out.errors.put("port", p.error);

        String user = username == null ? "" : username.trim();
        String pass = password == null ? "" : password;
        if (CONTROL.matcher(user).find()) {
            out.errors.put("username", "El usuario tiene caracteres no permitidos.");
        } else if (user.getBytes(StandardCharsets.UTF_8).length > CRED_MAX_BYTES) {
            out.errors.put("username", "El usuario es demasiado largo (máximo 255 bytes).");
        }
        if (CONTROL.matcher(pass).find()) {
            out.errors.put("password", "La contraseña tiene caracteres no permitidos.");
        } else if (pass.getBytes(StandardCharsets.UTF_8).length > CRED_MAX_BYTES) {
            out.errors.put("password", "La contraseña es demasiado larga (máximo 255 bytes).");
        }
        if (!pass.isEmpty() && user.isEmpty() && !out.errors.containsKey("username")) {
            out.errors.put("username", "Si usas contraseña, escribe también el usuario.");
        }
        out.username = user;
        out.password = pass;
        return out;
    }

    // ------------------------------------------------------------------ direcciones web

    public static final class UrlResult {
        public final boolean ok;
        public final String kind; // "url" o "search"
        public final String url;
        public final String error;

        private UrlResult(boolean ok, String kind, String url, String error) {
            this.ok = ok;
            this.kind = kind;
            this.url = url;
            this.error = error;
        }

        static UrlResult ok(String kind, String url) {
            return new UrlResult(true, kind, url, null);
        }

        static UrlResult fail(String error) {
            return new UrlResult(false, null, null, error);
        }
    }

    private static int indexOfAny(String s, String chars) {
        for (int i = 0; i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) return i;
        }
        return -1;
    }

    private static UrlResult buildWebUrl(String text) {
        int schemeEnd = text.indexOf("://");
        if (schemeEnd < 0) {
            // por ejemplo «http:ejemplo.com»
            return UrlResult.fail("La dirección no es válida.");
        }
        String scheme = text.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return UrlResult.fail("Sólo se pueden abrir direcciones web (http o https).");
        }
        String rest = text.substring(schemeEnd + 3);
        int end = indexOfAny(rest, "/?#");
        String authority = end < 0 ? rest : rest.substring(0, end);
        if (authority.isEmpty()) return UrlResult.fail("La dirección no es válida.");
        if (authority.indexOf('@') >= 0) {
            return UrlResult.fail("No se permiten direcciones con usuario y contraseña incrustados.");
        }
        String host;
        String port = null;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) return UrlResult.fail("La dirección no es válida.");
            host = authority.substring(1, close);
            String after = authority.substring(close + 1);
            if (!after.isEmpty()) {
                if (!after.startsWith(":")) return UrlResult.fail("La dirección no es válida.");
                port = after.substring(1);
            }
            if (!isIPv6(host)) return UrlResult.fail("La dirección no es válida.");
        } else {
            int c = authority.lastIndexOf(':');
            if (c >= 0) {
                host = authority.substring(0, c);
                port = authority.substring(c + 1);
            } else {
                host = authority;
            }
            if (host.isEmpty() || !HOST_CHARS.matcher(host).matches()) {
                return UrlResult.fail("La dirección no es válida.");
            }
        }
        if (port != null && !port.isEmpty()) {
            if (!port.matches("^\\d{1,5}$") || Integer.parseInt(port) < 1 || Integer.parseInt(port) > 65535) {
                return UrlResult.fail("La dirección no es válida.");
            }
        } else if (port != null) {
            return UrlResult.fail("La dirección no es válida.");
        }
        return UrlResult.ok("url", scheme + "://" + rest);
    }

    private static UrlResult searchFor(String t) {
        String enc;
        try {
            enc = URLEncoder.encode(t, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            enc = t;
        }
        return UrlResult.ok("search", SEARCH_URL + enc);
    }

    /** Convierte lo que se escribe en la barra de direcciones en una URL web o en una búsqueda. */
    public static UrlResult normalizeUserUrl(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return UrlResult.fail("Escribe una dirección web.");
        if (t.length() > URL_MAX) return UrlResult.fail("La dirección es demasiado larga.");
        if (CONTROL.matcher(t).find()) return UrlResult.fail("La dirección tiene caracteres no permitidos.");

        if (HTTP_SCHEME.matcher(t).find()) return buildWebUrl(t);
        if (t.matches("(?s).*\\s.*")) return searchFor(t);

        Matcher scheme = ANY_SCHEME.matcher(t);
        if (scheme.find()) {
            boolean looksLikeHostPort = HOST_PORT_START.matcher(t).matches(); // localhost:3000, ejemplo.com:8080
            if (!looksLikeHostPort) {
                String sc = scheme.group(1).toLowerCase(Locale.ROOT);
                if (sc.equals("http") || sc.equals("https")) return buildWebUrl(t);
                return UrlResult.fail("Sólo se pueden abrir direcciones web (http o https).");
            }
        }

        Matcher m = AUTHORITY.matcher(t);
        String authority = m.find() ? m.group(1) : t;
        boolean isHost = false;
        boolean isLocal = false;
        Matcher v6 = V6_AUTH.matcher(authority);
        if (v6.matches()) {
            isHost = isIPv6(v6.group(1));
            isLocal = true;
        } else {
            Matcher hp = HOST_PORT.matcher(authority);
            if (hp.matches()) {
                String hostPart = hp.group(1).toLowerCase(Locale.ROOT);
                if (hostPart.equals("localhost") || isIPv4(hostPart)) {
                    isHost = true;
                    isLocal = true;
                } else if (DOMAIN_LIKE.matcher(hostPart).matches()) {
                    isHost = true;
                }
            }
        }
        if (isHost) return buildWebUrl((isLocal ? "http" : "https") + "://" + t);
        return searchFor(t);
    }

    /** Sólo http(s) puede cargarse como página principal. */
    public static boolean isWebUrl(String url) {
        return url != null && HTTP_SCHEME.matcher(url).find();
    }

    // ------------------------------------------------------------------ pegar un proxy

    public static final class ParsedProxy {
        public String protocol; // puede ser null (no venía en el texto)
        public String host = "";
        public String port = "";
        public String username = "";
        public String password = "";
        public String error; // null si todo bien

        public boolean ok() {
            return error == null;
        }
    }

    private static String percentDecode(String s) {
        if (s.indexOf('%') < 0) return s;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] raw = s.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] == '%' && i + 2 < raw.length) {
                int hi = Character.digit((char) raw[i + 1], 16);
                int lo = Character.digit((char) raw[i + 2], 16);
                if (hi >= 0 && lo >= 0) {
                    out.write(hi * 16 + lo);
                    i += 2;
                    continue;
                }
            }
            out.write(raw[i]);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static ParsedProxy parseFail(String msg) {
        ParsedProxy p = new ParsedProxy();
        p.error = msg;
        return p;
    }

    private static boolean isPortText(String s) {
        return s != null && s.matches("^\\d{1,5}$");
    }

    /**
     * Interpreta un proxy pegado: «socks5://usuario:clave@host:1080», «usuario:clave@host:8080»,
     * «host:puerto» o «host:puerto:usuario:clave».
     */
    public static ParsedProxy parseProxyText(String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.isEmpty()) return parseFail("Pega primero los datos del proxy.");
        if (t.matches("(?s).*\\s.*")) return parseFail("No entendí ese formato: no debe tener espacios.");
        ParsedProxy p = new ParsedProxy();
        String rest = t;
        int se = rest.indexOf("://");
        if (se >= 0) {
            String sc = rest.substring(0, se).toLowerCase(Locale.ROOT);
            rest = rest.substring(se + 3);
            if (sc.equals("http")) p.protocol = "http";
            else if (sc.equals("https")) p.protocol = "https";
            else if (sc.equals("socks5") || sc.equals("socks5h") || sc.equals("socks")) p.protocol = "socks5";
            else if (sc.startsWith("socks4")) return parseFail("SOCKS4 no está soportado. Usa SOCKS5, HTTP o HTTPS.");
            else return parseFail("Tipo de proxy desconocido: " + sc);
            int slash = indexOfAny(rest, "/?#");
            if (slash >= 0) rest = rest.substring(0, slash);
        }
        int at = rest.lastIndexOf('@');
        if (at >= 0) {
            String cred = rest.substring(0, at);
            rest = rest.substring(at + 1);
            int c = cred.indexOf(':');
            if (c >= 0) {
                p.username = percentDecode(cred.substring(0, c));
                p.password = percentDecode(cred.substring(c + 1));
            } else {
                p.username = percentDecode(cred);
            }
            if (!splitHostPort(rest, p)) return parseFail("No entendí ese formato. Ejemplo: socks5://usuario:clave@host:1080");
            return p;
        }
        if (rest.startsWith("[")) {
            if (!splitHostPort(rest, p)) return parseFail("No entendí ese formato. Ejemplo: socks5://usuario:clave@host:1080");
            return p;
        }
        String[] parts = rest.split(":", -1);
        if (parts.length == 2 && isPortText(parts[1])) {
            p.host = parts[0];
            p.port = parts[1];
            return p;
        }
        if (parts.length == 4 && isPortText(parts[1])) { // host:puerto:usuario:clave
            p.host = parts[0];
            p.port = parts[1];
            p.username = parts[2];
            p.password = parts[3];
            return p;
        }
        if (parts.length == 4 && isPortText(parts[3])) { // usuario:clave:host:puerto
            p.username = parts[0];
            p.password = parts[1];
            p.host = parts[2];
            p.port = parts[3];
            return p;
        }
        return parseFail("No entendí ese formato. Ejemplos: host:puerto, usuario:clave@host:puerto o socks5://host:1080");
    }

    private static boolean splitHostPort(String hp, ParsedProxy p) {
        if (hp.startsWith("[")) {
            int close = hp.indexOf(']');
            if (close < 0) return false;
            p.host = hp.substring(1, close);
            String after = hp.substring(close + 1);
            if (after.startsWith(":") && isPortText(after.substring(1))) {
                p.port = after.substring(1);
                return true;
            }
            return false;
        }
        int c = hp.lastIndexOf(':');
        if (c <= 0 || hp.indexOf(':') != c) return false;
        p.host = hp.substring(0, c);
        p.port = hp.substring(c + 1);
        return isPortText(p.port);
    }
}
