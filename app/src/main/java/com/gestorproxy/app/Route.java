package com.gestorproxy.app;

import com.gestorproxy.core.Bridge;
import com.gestorproxy.core.ProxyDef;
import com.gestorproxy.core.SessionDef;
import com.gestorproxy.core.Store;
import com.gestorproxy.core.Upstream;

/** Por dónde debe salir el tráfico de una sesión ahora mismo, según su interruptor y su configuración. */
final class Route {
    final Bridge.Mode mode;
    final Upstream upstream; // sólo si mode == PROXY
    final ProxyDef proxy; // el proxy elegido (puede existir aunque el interruptor esté apagado)
    final String reason; // explicación cuando mode == BLOCKED

    private Route(Bridge.Mode mode, Upstream upstream, ProxyDef proxy, String reason) {
        this.mode = mode;
        this.upstream = upstream;
        this.proxy = proxy;
        this.reason = reason == null ? "" : reason;
    }

    static Route of(SessionDef s, Store.Data d) {
        return build(s, d, true);
    }

    /** Igual que of(), pero sin descifrar la contraseña (para dibujar listas). El resultado no tiene «upstream». */
    static Route preview(SessionDef s, Store.Data d) {
        return build(s, d, false);
    }

    private static Route build(SessionDef s, Store.Data d, boolean decrypt) {
        ProxyDef p = s.proxyId == null ? null : d.proxy(s.proxyId);
        if (s.proxyEnabled) {
            if (p == null) {
                return new Route(Bridge.Mode.BLOCKED, null, null, "El proxy de esta sesión ya no existe. Edita la sesión y elige otro.");
            }
            if (!decrypt) return new Route(Bridge.Mode.PROXY, null, p, "");
            try {
                return new Route(Bridge.Mode.PROXY, AppFiles.upstreamFor(p), p, "");
            } catch (IllegalStateException e) {
                return new Route(Bridge.Mode.BLOCKED, null, p, e.getMessage());
            }
        }
        if (SessionDef.OFF_DIRECT.equals(s.onProxyOff)) {
            return new Route(Bridge.Mode.DIRECT, null, p, "");
        }
        return new Route(Bridge.Mode.BLOCKED, null, p, "Proxy apagado: esta sesión está configurada para no conectarse sin proxy.");
    }

    /** Identifica la ruta de salida (modo y proxy): cambia sólo cuando cambia por dónde sale el tráfico. */
    String key() {
        return mode + "|" + (mode == Bridge.Mode.PROXY && proxy != null
                ? proxy.id + "|" + proxy.protocol + "|" + proxy.host + "|" + proxy.port + "|" + proxy.username + "|" + proxy.passwordEnc
                : "");
    }

    /** Texto corto para la barra de la sesión. */
    String label() {
        switch (mode) {
            case PROXY:
                return "Sale por el proxy «" + proxy.name + "» (" + proxy.describe() + ")";
            case DIRECT:
                return "Proxy apagado: sale directo, con tu conexión real";
            default:
                return "Sin conexión: " + reason;
        }
    }

    String chipText() {
        switch (mode) {
            case PROXY:
                return "PROXY";
            case DIRECT:
                return "DIRECTO";
            default:
                return "BLOQUEADO";
        }
    }
}
