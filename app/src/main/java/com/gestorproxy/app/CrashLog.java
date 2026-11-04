package com.gestorproxy.app;

import android.content.Context;
import com.gestorproxy.core.AddrUtil;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Guarda el último error inesperado (de cualquier proceso de la app) para poder mostrarlo después en el gestor.
 * Sirve para que una persona sin conocimientos pueda enseñar una captura del fallo.
 */
final class CrashLog {
    private CrashLog() {}

    private static File file(Context c) {
        File d = new File(c.getFilesDir(), "gp");
        if (!d.exists()) d.mkdirs();
        return new File(d, "ultimo-error.txt");
    }

    static void install(final Context app) {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    record(app, "Error inesperado", e);
                } catch (Throwable ignored) {
                    // nunca fallar al registrar un fallo
                }
                if (previous != null) previous.uncaughtException(t, e);
            }
        });
    }

    /** Guarda un error (aunque la app no se haya cerrado). */
    static void record(Context c, String what, Throwable e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println(what + " — " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        pw.println("Proceso: " + processName());
        pw.println("Android " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + "), "
                + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
        pw.println();
        if (e != null) e.printStackTrace(pw);
        pw.flush();
        String text = sw.toString();
        if (text.length() > 6000) text = text.substring(0, 6000) + "\n…";
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file(c));
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // nada
        } finally {
            AddrUtil.closeQuietly(out);
        }
    }

    static String processName() {
        try {
            String n = android.app.Application.getProcessName();
            return n == null ? "?" : n;
        } catch (Throwable t) {
            return "?";
        }
    }

    static String read(Context c) {
        File f = file(c);
        if (!f.exists() || f.length() == 0) return null;
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) >= 0) b.write(buf, 0, n);
                return new String(b.toByteArray(), StandardCharsets.UTF_8);
            } finally {
                AddrUtil.closeQuietly(in);
            }
        } catch (IOException e) {
            return null;
        }
    }

    static void clear(Context c) {
        file(c).delete();
    }
}
