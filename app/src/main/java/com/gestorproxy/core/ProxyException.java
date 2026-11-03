package com.gestorproxy.core;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.util.Locale;
import javax.net.ssl.SSLException;

/** Error de conexión con un mensaje claro en español y un código estable para el programa. */
public class ProxyException extends IOException {
    private static final long serialVersionUID = 1L;

    /** Mensaje cuando el proxy cierra la conexión sin contestar (casi siempre es un tipo de proxy equivocado). */
    public static final String CLOSED_MSG = "El proxy cerró la conexión sin responder. ¿Elegiste el tipo correcto (HTTP, HTTPS o SOCKS5)?";

    public final String code;

    public ProxyException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ProxyException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    private static boolean hasCertCause(Throwable t) {
        int depth = 0;
        while (t != null && depth++ < 8) {
            if (t instanceof CertificateException) return true;
            String n = t.getClass().getName();
            if (n.contains("CertPath") || n.contains("Certificate")) return true;
            t = t.getCause();
        }
        return false;
    }

    /** Traduce cualquier error de red a un ProxyException claro. {@code proxyStage}: ¿falló el tramo hasta el proxy? */
    public static ProxyException from(Throwable err, boolean proxyStage) {
        if (err instanceof ProxyException) return (ProxyException) err;
        String msg = err == null || err.getMessage() == null ? "" : err.getMessage();
        String low = msg.toLowerCase(Locale.ROOT);
        if (err instanceof UnknownHostException) {
            return new ProxyException(
                    "DNS",
                    proxyStage
                            ? "No se encontró la dirección del proxy. ¿Está bien escrita?"
                            : "No se encontró la dirección del destino.",
                    err);
        }
        if (err instanceof SocketTimeoutException) {
            return new ProxyException("TIMEOUT", proxyStage ? "El proxy no respondió a tiempo." : "El servicio no respondió a tiempo.", err);
        }
        if (err instanceof SSLException) {
            if (hasCertCause(err)) {
                return new ProxyException("TLS_CERT", "El certificado de seguridad no es de confianza.", err);
            }
            return new ProxyException(
                    "TLS_PROTO",
                    proxyStage
                            ? "El proxy no responde con HTTPS. Prueba con el tipo HTTP."
                            : "No se pudo establecer una conexión segura con el servicio de IP.",
                    err);
        }
        if (err instanceof ConnectException) {
            if (low.contains("timed out") || low.contains("etimedout")) {
                return new ProxyException("TIMEOUT", "El proxy no respondió a tiempo.", err);
            }
            if (low.contains("unreachable") || low.contains("enetunreach") || low.contains("ehostunreach")) {
                return new ProxyException("UNREACHABLE", "No hay ruta hacia el proxy. Revisa tu conexión a internet.", err);
            }
            return new ProxyException(
                    "REFUSED",
                    "El proxy rechazó la conexión. ¿El puerto es correcto y el proxy está encendido?",
                    err);
        }
        if (err instanceof NoRouteToHostException) {
            return new ProxyException("UNREACHABLE", "No hay ruta hacia el proxy. Revisa tu conexión a internet.", err);
        }
        if (err instanceof EOFException) {
            return new ProxyException("CLOSED", CLOSED_MSG, err);
        }
        if (err instanceof SocketException) {
            if (low.contains("reset") || low.contains("broken pipe") || low.contains("abort")) {
                return new ProxyException(
                        "RESET",
                        "El proxy cerró la conexión de golpe. ¿Elegiste el tipo correcto (HTTP, HTTPS o SOCKS5)?",
                        err);
            }
            if (low.contains("unreachable")) {
                return new ProxyException("UNREACHABLE", "No hay ruta hacia el proxy. Revisa tu conexión a internet.", err);
            }
            if (low.contains("closed")) {
                return new ProxyException("CLOSED", CLOSED_MSG, err);
            }
        }
        return new ProxyException(
                "ERROR",
                msg.isEmpty() ? "Error de red desconocido." : "Error de red: " + msg,
                err);
    }

    /** Como {@link #from}, pero para conexiones SIN proxy (los mensajes hablan del sitio, no del proxy). */
    public static ProxyException fromDirect(Throwable err) {
        if (err instanceof ProxyException) return (ProxyException) err;
        String msg = err == null || err.getMessage() == null ? "" : err.getMessage();
        String low = msg.toLowerCase(Locale.ROOT);
        if (err instanceof UnknownHostException) {
            return new ProxyException("DNS", "No se encontró ese sitio (error de DNS).", err);
        }
        if (err instanceof SocketTimeoutException || (err instanceof ConnectException && low.contains("timed out"))) {
            return new ProxyException("TIMEOUT", "El sitio no respondió a tiempo.", err);
        }
        if (err instanceof NoRouteToHostException || low.contains("unreachable")) {
            return new ProxyException("UNREACHABLE", "No hay conexión a internet.", err);
        }
        if (err instanceof ConnectException) {
            return new ProxyException("REFUSED", "El sitio rechazó la conexión.", err);
        }
        return new ProxyException("ERROR", msg.isEmpty() ? "Error de red desconocido." : "Error de red: " + msg, err);
    }
}
