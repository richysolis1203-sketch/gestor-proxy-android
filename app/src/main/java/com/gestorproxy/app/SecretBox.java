package com.gestorproxy.app;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Cifra las contraseñas de los proxies con una clave guardada en el Android Keystore (la clave no sale del
 * teléfono y no se puede extraer). Si el teléfono no lo permite, se guarda sólo codificada y la app lo avisa.
 */
final class SecretBox {
    private SecretBox() {}

    private static final String ALIAS = "gestor-proxy-passwords-v1";
    private static final String PROVIDER = "AndroidKeyStore";
    private static final String CIPHER = "AES/GCM/NoPadding";

    private static synchronized SecretKey key() throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance(PROVIDER);
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            KeyStore.Entry e = ks.getEntry(ALIAS, null);
            if (e instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) e).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER);
        kg.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    private static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    /** Devuelve el texto cifrado listo para guardar ("" si no hay contraseña). */
    static String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        byte[] raw = plain.getBytes(StandardCharsets.UTF_8);
        try {
            Cipher c = Cipher.getInstance(CIPHER);
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(raw);
            return "v1:" + b64(iv) + ":" + b64(ct);
        } catch (Exception e) {
            return "plain:" + b64(raw); // sin cifrado real: la pantalla de proxies lo avisa
        }
    }

    /** Devuelve la contraseña en claro, o null si no se puede leer. */
    static String decrypt(String blob) {
        if (blob == null || blob.isEmpty()) return "";
        try {
            if (blob.startsWith("plain:")) {
                return new String(Base64.getDecoder().decode(blob.substring(6)), StandardCharsets.UTF_8);
            }
            if (!blob.startsWith("v1:")) return null;
            String[] parts = blob.split(":");
            if (parts.length != 3) return null;
            byte[] iv = Base64.getDecoder().decode(parts[1]);
            byte[] ct = Base64.getDecoder().decode(parts[2]);
            Cipher c = Cipher.getInstance(CIPHER);
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean isWeak(String blob) {
        return blob != null && blob.startsWith("plain:");
    }
}
