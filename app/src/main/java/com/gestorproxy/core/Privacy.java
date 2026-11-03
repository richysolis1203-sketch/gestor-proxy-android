package com.gestorproxy.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Ajustes de privacidad de una sesión: configuración del script anti-huella y texto de «User-Agent» reducido. */
public final class Privacy {
    private Privacy() {}

    /** ¿Hay que inyectar el script de huella digital / señales de privacidad? */
    public static boolean needsContentScript(SessionDef s) {
        return s.fingerprintProtection || s.sendPrivacySignals;
    }

    /** ¿Alguna protección depende de poder ejecutar un script antes de que cargue la página? */
    public static boolean needsDocumentStartScript(SessionDef s) {
        return needsContentScript(s) || s.webrtcProtection;
    }

    /** Configuración para gpProtect(cfg) (ver assets/gp-protect.js). */
    public static String contentConfigJson(SessionDef s) {
        Map<String, Object> cfg = new LinkedHashMap<String, Object>();
        boolean fp = s.fingerprintProtection;
        cfg.put("enabled", Boolean.valueOf(needsContentScript(s)));
        cfg.put("seed", Long.valueOf(s.seed));
        cfg.put("gpc", Boolean.valueOf(s.sendPrivacySignals));
        cfg.put("hardware", Boolean.valueOf(fp));
        cfg.put("hardwareConcurrency", Long.valueOf(8));
        cfg.put("deviceMemory", Long.valueOf(4));
        // El idioma no se cambia: la cabecera Accept-Language sigue siendo la del teléfono y debe coincidir.
        cfg.put("languages", new ArrayList<Object>());
        cfg.put("canvas", Boolean.valueOf(fp));
        cfg.put("webgl", Boolean.valueOf(fp));
        cfg.put("audio", Boolean.valueOf(fp));
        cfg.put("glVendor", "Qualcomm");
        cfg.put("glRenderer", "Adreno (TM) 650");
        return Json.stringify(cfg);
    }

    /**
     * Script que se ejecuta al inicio de cada página: huella digital (gpProtect) y bloqueo de WebRTC.
     * Cada parte va protegida para que un fallo nunca rompa la página.
     */
    public static String buildScript(String gpProtectSource, String webrtcSource, SessionDef s) {
        StringBuilder sb = new StringBuilder();
        if (needsContentScript(s) && gpProtectSource != null && !gpProtectSource.isEmpty()) {
            sb.append("try{(").append(gpProtectSource).append(")(").append(contentConfigJson(s)).append(");}catch(e){}\n");
        }
        if (s.webrtcProtection && webrtcSource != null && !webrtcSource.isEmpty()) {
            sb.append("try{").append(webrtcSource).append("\n}catch(e){}\n");
        }
        return sb.toString();
    }

    private static final Pattern CHROME = Pattern.compile("Chrome/(\\d+)");

    /**
     * Quita del «User-Agent» el modelo del teléfono, el número de compilación y la marca «wv»,
     * igual que hace Chrome con su User-Agent reducido: «Linux; Android 10; K».
     */
    public static String reduceUserAgent(String ua) {
        if (ua == null) return "";
        Matcher m = CHROME.matcher(ua);
        if (!m.find()) return ua.replace("; wv", "");
        boolean mobile = ua.contains(" Mobile");
        return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + m.group(1) + ".0.0.0"
                + (mobile ? " Mobile" : "") + " Safari/537.36";
    }

    /** Página de inicio de una sesión (HTML sencillo; el nombre se escapa). */
    public static String startPageHtml(String sessionName) {
        String esc = escape(sessionName);
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Sesión</title><style>body{font-family:sans-serif;margin:0;padding:32px 24px;background:#f4f6f8;color:#1b2733}"
                + "h1{font-size:22px;margin:0 0 8px}p{line-height:1.5;color:#44525f}.box{background:#fff;border-radius:14px;padding:20px;"
                + "margin-top:20px;box-shadow:0 1px 3px rgba(0,0,0,.08)}</style></head><body><h1>" + esc
                + "</h1><p>Escribe una dirección o una búsqueda en la barra de arriba.</p>"
                + "<div class=\"box\"><p><b>Esta ventana es una sesión aparte.</b> Sus cookies, su almacenamiento y su historial "
                + "no se comparten con ninguna otra sesión.</p><p>La barra de color de arriba te dice por dónde sale la conexión. "
                + "Esta app reduce el rastreo, pero no te hace anónimo.</p></div></body></html>";
    }

    /** Página de error mostrada cuando falla la carga de la página principal. */
    public static String errorPageHtml(String title, String detail) {
        String t = escape(title);
        String d = escape(detail);
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Error</title><style>body{font-family:sans-serif;margin:0;padding:32px 24px;background:#f4f6f8;color:#1b2733}"
                + "h1{font-size:20px;margin:0 0 12px}p{line-height:1.5;color:#44525f}.box{background:#fff;border-radius:14px;padding:18px;"
                + "box-shadow:0 1px 3px rgba(0,0,0,.08)}</style></head><body><div class=\"box\"><h1>" + t + "</h1><p>" + d
                + "</p></div></body></html>";
    }

    private static String escape(String n) {
        if (n == null) return "";
        return n.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
