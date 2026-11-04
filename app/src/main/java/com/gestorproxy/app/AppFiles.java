package com.gestorproxy.app;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import com.gestorproxy.core.AddrUtil;
import com.gestorproxy.core.ProxyDef;
import com.gestorproxy.core.SessionDef;
import com.gestorproxy.core.Store;
import com.gestorproxy.core.Upstream;
import com.gestorproxy.core.Validate;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rutas y operaciones con archivos compartidas por el gestor y por los procesos de las sesiones. */
final class AppFiles {
    private AppFiles() {}

    static File root(Context c) {
        File f = new File(c.getFilesDir(), "gp");
        if (!f.exists()) f.mkdirs();
        return f;
    }

    static Store store(Context c) {
        return new Store(root(c));
    }

    /** Carpeta propia de una sesión (historial, última dirección, marcas). El id se valida: nunca es una ruta. */
    static File sessionDir(Context c, String sid) {
        if (!Validate.isSessionId(sid)) throw new IllegalArgumentException("Identificador de sesión no válido");
        File d = new File(new File(root(c), "sessions"), sid);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static File aliveLockFile(Context c, String sid) {
        return new File(sessionDir(c, sid), "alive.lock");
    }

    static File closeRequestFile(Context c, String sid) {
        return new File(sessionDir(c, sid), "close.req");
    }

    static File lastUrlFile(Context c, String sid) {
        return new File(sessionDir(c, sid), "last-url.txt");
    }

    static File historyFile(Context c, String sid) {
        return new File(sessionDir(c, sid), "history.jsonl");
    }

    // ------------------------------------------------------------------ ¿la sesión está abierta?

    /**
     * Una sesión abierta mantiene un bloqueo sobre su archivo «alive.lock» mientras vive su proceso. Aquí se intenta
     * tomar ese bloqueo: si no se puede, la sesión está abierta. (Nunca llamar desde el propio proceso de la sesión.)
     */
    static boolean isSessionAlive(Context c, String sid) {
        File f = aliveLockFile(c, sid);
        if (!f.exists()) return false;
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "rw");
            FileLock l = raf.getChannel().tryLock();
            if (l == null) return true;
            l.release();
            return false;
        } catch (OverlappingFileLockException e) {
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            AddrUtil.closeQuietly(raf);
        }
    }

    static void requestClose(Context c, String sid) {
        try {
            FileOutputStream out = new FileOutputStream(closeRequestFile(c, sid));
            try {
                out.write(String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
            } finally {
                AddrUtil.closeQuietly(out);
            }
        } catch (IOException ignored) {
            // si no se puede escribir, se intenta cerrar la tarea de todas formas
        }
        finishTaskOf(c, sid);
    }

    static boolean closeRequested(Context c, String sid) {
        return closeRequestFile(c, sid).exists();
    }

    static void clearCloseRequest(Context c, String sid) {
        closeRequestFile(c, sid).delete();
    }

    /** Pide al sistema que cierre la tarea (ventana) de la sesión, si existe. */
    private static void finishTaskOf(Context c, String sid) {
        try {
            Store.Data d = store(c).read();
            SessionDef s = d.session(sid);
            if (s == null) return;
            Class<?> cls = SessionActivity.classForSlot(s.slot);
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return;
            List<ActivityManager.AppTask> tasks = am.getAppTasks();
            if (tasks == null) return;
            for (ActivityManager.AppTask t : tasks) {
                ActivityManager.RecentTaskInfo info = t.getTaskInfo();
                Intent base = info == null ? null : info.baseIntent;
                ComponentName comp = base == null ? null : base.getComponent();
                if (comp != null && comp.getClassName().equals(cls.getName())) t.finishAndRemoveTask();
            }
        } catch (RuntimeException ignored) {
            // no es crítico
        }
    }

    // ------------------------------------------------------------------ borrar datos

    private static void collect(File dir, String needle, int depth, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.getName().contains(needle)) {
                out.add(k);
            } else if (depth > 0 && k.isDirectory()) {
                collect(k, needle, depth - 1, out);
            }
        }
    }

    /** Carpetas del motor del navegador (WebView) que pertenecen a esta sesión. */
    static List<File> webViewDirs(Context c, String sid) {
        String needle = SessionDef.webViewSuffix(sid);
        List<File> found = new java.util.ArrayList<File>();
        collect(c.getDataDir(), needle, 1, found);
        collect(c.getCacheDir(), needle, 2, found);
        collect(c.getCodeCacheDir(), needle, 1, found);
        return found;
    }

    /**
     * Borra cookies, almacenamiento, caché e historial de una sesión CERRADA. Devuelve true si no queda nada.
     */
    static boolean wipeSessionData(Context c, String sid) {
        for (File f : webViewDirs(c, sid)) AddrUtil.deleteRecursively(f);
        File d = sessionDir(c, sid);
        historyFile(c, sid).delete();
        lastUrlFile(c, sid).delete();
        closeRequestFile(c, sid).delete();
        File[] extra = d.listFiles();
        if (extra != null) {
            for (File f : extra) {
                if (!f.getName().equals("alive.lock")) AddrUtil.deleteRecursively(f);
            }
        }
        return webViewDirs(c, sid).isEmpty();
    }

    private static final Pattern ORPHAN = Pattern.compile("gps[0-9a-f]{16}");

    private static int cleanOrphansIn(File dir, Set<String> valid, int depth) {
        File[] kids = dir.listFiles();
        if (kids == null) return 0;
        int n = 0;
        for (File k : kids) {
            Matcher m = ORPHAN.matcher(k.getName());
            if (m.find()) {
                if (!valid.contains(m.group())) {
                    AddrUtil.deleteRecursively(k);
                    n++;
                }
            } else if (depth > 0 && k.isDirectory()) {
                n += cleanOrphansIn(k, valid, depth - 1);
            }
        }
        return n;
    }

    /**
     * Borra datos del navegador y carpetas de sesiones que ya no tienen sesión (por ejemplo, restos de una sesión
     * eliminada). Nunca toca las de sesiones que existen. Devuelve cuántas carpetas borró.
     */
    static int cleanOrphans(Context c, Store.Data d) {
        Set<String> valid = new HashSet<String>();
        for (SessionDef s : d.sessions) valid.add(SessionDef.webViewSuffix(s.id));
        int n = 0;
        n += cleanOrphansIn(c.getDataDir(), valid, 1);
        n += cleanOrphansIn(c.getCacheDir(), valid, 2);
        n += cleanOrphansIn(c.getCodeCacheDir(), valid, 1);
        File[] kids = new File(root(c), "sessions").listFiles();
        if (kids != null) {
            for (File k : kids) {
                if (Validate.isSessionId(k.getName()) && d.session(k.getName()) == null) {
                    AddrUtil.deleteRecursively(k);
                    n++;
                }
            }
        }
        return n;
    }

    /** Borra además la carpeta propia de la sesión (al eliminarla). */
    static void deleteSessionFolder(Context c, String sid) {
        AddrUtil.deleteRecursively(new File(new File(root(c), "sessions"), sid));
    }

    // ------------------------------------------------------------------ proxy listo para usar

    /** Convierte un proxy guardado en uno utilizable (descifra la contraseña). */
    static Upstream upstreamFor(ProxyDef p) {
        String pass = "";
        if (p.hasPassword()) {
            String d = SecretBox.decrypt(p.passwordEnc);
            if (d == null) {
                throw new IllegalStateException(
                        "No se pudo leer la contraseña guardada del proxy «" + p.name + "». Edítalo y vuelve a escribirla.");
            }
            pass = d;
        }
        return new Upstream(p.protocol, p.host, p.port, p.username, pass);
    }
}
