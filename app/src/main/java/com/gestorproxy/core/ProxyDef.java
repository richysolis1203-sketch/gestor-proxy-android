package com.gestorproxy.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** Un proxy guardado. La contraseña se guarda ya cifrada (passwordEnc); este código no la conoce en claro. */
public final class ProxyDef {
    public String id = "";
    public String name = "";
    public String protocol = "socks5"; // http | https | socks5
    public String host = "";
    public int port = 1080;
    public String username = "";
    public String passwordEnc = ""; // "" = sin contraseña

    public ProxyDef copy() {
        ProxyDef p = new ProxyDef();
        p.id = id;
        p.name = name;
        p.protocol = protocol;
        p.host = host;
        p.port = port;
        p.username = username;
        p.passwordEnc = passwordEnc;
        return p;
    }

    public boolean hasPassword() {
        return passwordEnc != null && !passwordEnc.isEmpty();
    }

    /** «socks5://host:1080» (nunca incluye usuario ni contraseña). */
    public String describe() {
        return protocol + "://" + (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", id);
        m.put("name", name);
        m.put("protocol", protocol);
        m.put("host", host);
        m.put("port", Long.valueOf(port));
        m.put("username", username);
        m.put("passwordEnc", passwordEnc);
        return m;
    }

    /** Devuelve null si el registro no es válido (se ignora en vez de fallar). */
    public static ProxyDef fromMap(Map<String, Object> m) {
        if (m == null) return null;
        ProxyDef p = new ProxyDef();
        p.id = Json.str(m.get("id"), "");
        if (!Validate.isProxyId(p.id)) return null;
        Validate.Check n = Validate.validateName(Json.str(m.get("name"), ""), "el proxy");
        if (!n.ok()) return null;
        p.name = n.value;
        p.protocol = Json.str(m.get("protocol"), "");
        if (!Validate.isProtocol(p.protocol)) return null;
        p.host = Json.str(m.get("host"), "");
        if (p.host.isEmpty() || p.host.length() > Validate.HOST_MAX) return null;
        long port = Json.lng(m.get("port"), 0);
        if (port < 1 || port > 65535) return null;
        p.port = (int) port;
        p.username = Json.str(m.get("username"), "");
        p.passwordEnc = Json.str(m.get("passwordEnc"), "");
        return p;
    }
}
