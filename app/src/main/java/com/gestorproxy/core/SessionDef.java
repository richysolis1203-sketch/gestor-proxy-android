package com.gestorproxy.core;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/** Una sesión: su propio proceso, sus propios datos de navegación y su propio proxy. */
public final class SessionDef {
    /** Número máximo de sesiones = número de procesos declarados en el manifiesto. */
    public static final int MAX_SESSIONS = 10;

    /** Colores de identificación (ARGB). */
    public static final int[] COLORS = {
        0xFF2563EB, 0xFF7C3AED, 0xFFDB2777, 0xFFDC2626, 0xFFEA580C, 0xFFCA8A04, 0xFF16A34A, 0xFF0891B2,
    };

    public static final String OFF_DIRECT = "direct";
    public static final String OFF_BLOCK = "block";

    public String id = "";
    public String name = "";
    public int slot = 0; // 0..9: proceso de Android que usa esta sesión
    public int color = 0; // índice en COLORS
    public int seed = 1; // semilla de la huella aleatoria de la sesión
    public String proxyId = null;
    public boolean proxyEnabled = false;
    public String onProxyOff = OFF_DIRECT; // qué pasa cuando el proxy está apagado
    public String startUrl = "";
    public boolean blockTrackers = true;
    public boolean blockThirdPartyCookies = true;
    public boolean fingerprintProtection = true;
    public boolean webrtcProtection = true;
    public boolean sendPrivacySignals = true;
    public boolean saveHistory = true;
    public boolean secureScreen = false;
    public long createdAt = 0;

    public SessionDef copy() {
        SessionDef s = new SessionDef();
        s.id = id;
        s.name = name;
        s.slot = slot;
        s.color = color;
        s.seed = seed;
        s.proxyId = proxyId;
        s.proxyEnabled = proxyEnabled;
        s.onProxyOff = onProxyOff;
        s.startUrl = startUrl;
        s.blockTrackers = blockTrackers;
        s.blockThirdPartyCookies = blockThirdPartyCookies;
        s.fingerprintProtection = fingerprintProtection;
        s.webrtcProtection = webrtcProtection;
        s.sendPrivacySignals = sendPrivacySignals;
        s.saveHistory = saveHistory;
        s.secureScreen = secureScreen;
        s.createdAt = createdAt;
        return s;
    }

    /** Nueva sesión con todo lo que protege encendido. */
    public static SessionDef create(String name, int slot, long now) {
        SessionDef s = new SessionDef();
        s.id = Validate.newId("s");
        s.name = name;
        s.slot = slot;
        s.color = slot % COLORS.length;
        s.seed = new SecureRandom().nextInt() | 1;
        s.createdAt = now;
        return s;
    }

    public int colorValue() {
        return COLORS[Math.floorMod(color, COLORS.length)];
    }

    /** Nombre del directorio de datos del WebView de esta sesión (ver SessionActivity). */
    public static String webViewSuffix(String sessionId) {
        return "gp" + sessionId.replace("-", "");
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", id);
        m.put("name", name);
        m.put("slot", Long.valueOf(slot));
        m.put("color", Long.valueOf(color));
        m.put("seed", Long.valueOf(seed));
        m.put("proxyId", proxyId);
        m.put("proxyEnabled", Boolean.valueOf(proxyEnabled));
        m.put("onProxyOff", onProxyOff);
        m.put("startUrl", startUrl);
        m.put("blockTrackers", Boolean.valueOf(blockTrackers));
        m.put("blockThirdPartyCookies", Boolean.valueOf(blockThirdPartyCookies));
        m.put("fingerprintProtection", Boolean.valueOf(fingerprintProtection));
        m.put("webrtcProtection", Boolean.valueOf(webrtcProtection));
        m.put("sendPrivacySignals", Boolean.valueOf(sendPrivacySignals));
        m.put("saveHistory", Boolean.valueOf(saveHistory));
        m.put("secureScreen", Boolean.valueOf(secureScreen));
        m.put("createdAt", Long.valueOf(createdAt));
        return m;
    }

    /** Devuelve null si el registro no es válido (se ignora en vez de fallar). */
    public static SessionDef fromMap(Map<String, Object> m) {
        if (m == null) return null;
        SessionDef s = new SessionDef();
        s.id = Json.str(m.get("id"), "");
        if (!Validate.isSessionId(s.id)) return null;
        Validate.Check n = Validate.validateName(Json.str(m.get("name"), ""), "la sesión");
        if (!n.ok()) return null;
        s.name = n.value;
        long slot = Json.lng(m.get("slot"), -1);
        if (slot < 0 || slot >= MAX_SESSIONS) return null;
        s.slot = (int) slot;
        s.color = (int) Math.floorMod(Json.lng(m.get("color"), 0), (long) COLORS.length);
        s.seed = (int) Json.lng(m.get("seed"), 1);
        if (s.seed == 0) s.seed = 1;
        Object pid = m.get("proxyId");
        s.proxyId = pid instanceof String && Validate.isProxyId((String) pid) ? (String) pid : null;
        s.proxyEnabled = Json.bool(m.get("proxyEnabled"), false) && s.proxyId != null;
        String off = Json.str(m.get("onProxyOff"), OFF_DIRECT);
        s.onProxyOff = OFF_DIRECT.equals(off) ? OFF_DIRECT : OFF_BLOCK;
        String start = Json.str(m.get("startUrl"), "");
        s.startUrl = Validate.isWebUrl(start) && start.length() <= Validate.URL_MAX ? start : "";
        s.blockTrackers = Json.bool(m.get("blockTrackers"), true);
        s.blockThirdPartyCookies = Json.bool(m.get("blockThirdPartyCookies"), true);
        s.fingerprintProtection = Json.bool(m.get("fingerprintProtection"), true);
        s.webrtcProtection = Json.bool(m.get("webrtcProtection"), true);
        s.sendPrivacySignals = Json.bool(m.get("sendPrivacySignals"), true);
        s.saveHistory = Json.bool(m.get("saveHistory"), true);
        s.secureScreen = Json.bool(m.get("secureScreen"), false);
        s.createdAt = Json.lng(m.get("createdAt"), 0);
        return s;
    }
}
