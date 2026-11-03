package com.gestorproxy.core;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.util.Locale;

/** Utilidades pequeñas: direcciones locales/privadas, cierre silencioso y borrado de carpetas. */
public final class AddrUtil {
    private AddrUtil() {}

    /** ¿Esta dirección ya resuelta es local, privada, de enlace local o reservada? */
    public static boolean isLocalAddress(InetAddress a) {
        if (a == null) return true;
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int b0 = b[0] & 0xff;
            int b1 = b[1] & 0xff;
            if (b0 == 0) return true;
            if (b0 == 100 && b1 >= 64 && b1 <= 127) return true; // CGNAT 100.64.0.0/10
            if (b0 >= 240) return true; // reservadas
        } else if (b.length == 16) {
            if ((b[0] & 0xfe) == 0xfc) return true; // fc00::/7 (privadas)
        }
        return false;
    }

    /** ¿El nombre de destino es claramente local (localhost, IP privada, *.local)? Sin consultar DNS. */
    public static boolean isLocalName(String host) {
        if (host == null) return true;
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        if (h.isEmpty()) return true;
        if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".localdomain")
                || h.endsWith(".internal") || h.endsWith(".lan") || h.endsWith(".home.arpa")) {
            return true;
        }
        if (Validate.isIPv4(h) || Validate.isIPv6(h)) {
            try {
                return isLocalAddress(InetAddress.getByName(h)); // es un literal: no consulta DNS
            } catch (UnknownHostException e) {
                return true;
            }
        }
        // Formas raras de IPv4 (decimal, hexadecimal, octal) que algunos sistemas aceptan: se rechazan.
        if (h.matches("^(0x[0-9a-f]+|[0-9]+)(\\.(0x[0-9a-f]+|[0-9]+))*$")) return true;
        return false;
    }

    public static void closeQuietly(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
            // nada
        } catch (RuntimeException ignored) {
            // nada
        }
    }

    /** Borra un archivo o carpeta sin seguir enlaces simbólicos. Devuelve true si ya no existe. */
    public static boolean deleteRecursively(File f) {
        if (f == null) return true;
        try {
            if (!f.exists() && !Files.isSymbolicLink(f.toPath())) return true;
            if (!Files.isSymbolicLink(f.toPath()) && f.isDirectory()) {
                File[] kids = f.listFiles();
                if (kids != null) {
                    for (File k : kids) deleteRecursively(k);
                }
            }
            return f.delete() || !f.exists();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
