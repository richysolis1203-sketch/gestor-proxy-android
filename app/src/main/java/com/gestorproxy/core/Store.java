package com.gestorproxy.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Almacén de proxies y sesiones en un archivo JSON. Lo pueden usar varios procesos de Android a la vez:
 * las escrituras se hacen bajo un bloqueo de archivo y son atómicas (archivo temporal + renombrar);
 * las lecturas no necesitan bloqueo.
 */
public final class Store {
    public static final int VERSION = 1;
    private static final Object JVM_LOCK = new Object();

    /** Contenido del almacén. */
    public static final class Data {
        public final List<ProxyDef> proxies = new ArrayList<ProxyDef>();
        public final List<SessionDef> sessions = new ArrayList<SessionDef>();
        /** Aviso para mostrar (no se guarda): por ejemplo, que se recuperó una copia de respaldo. */
        public String note;

        public ProxyDef proxy(String id) {
            if (id == null) return null;
            for (ProxyDef p : proxies) {
                if (p.id.equals(id)) return p;
            }
            return null;
        }

        public SessionDef session(String id) {
            if (id == null) return null;
            for (SessionDef s : sessions) {
                if (s.id.equals(id)) return s;
            }
            return null;
        }

        /** Primer proceso libre (0..9) o -1 si ya hay 10 sesiones. */
        public int freeSlot() {
            boolean[] used = new boolean[SessionDef.MAX_SESSIONS];
            for (SessionDef s : sessions) {
                if (s.slot >= 0 && s.slot < used.length) used[s.slot] = true;
            }
            for (int i = 0; i < used.length; i++) {
                if (!used[i]) return i;
            }
            return -1;
        }

        public int sessionsUsing(String proxyId) {
            int n = 0;
            for (SessionDef s : sessions) {
                if (proxyId != null && proxyId.equals(s.proxyId)) n++;
            }
            return n;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("version", Long.valueOf(VERSION));
            List<Object> ps = new ArrayList<Object>();
            for (ProxyDef p : proxies) ps.add(p.toMap());
            List<Object> ss = new ArrayList<Object>();
            for (SessionDef s : sessions) ss.add(s.toMap());
            m.put("proxies", ps);
            m.put("sessions", ss);
            return m;
        }
    }

    /** Cambio que se aplica con el almacén bloqueado. Puede lanzar IOException con un mensaje en español. */
    public interface Mutation<T> {
        T apply(Data data) throws IOException;
    }

    private final File dir;
    private final File file;
    private final File backup;
    private final File lockFile;

    public Store(File dir) {
        this.dir = dir;
        this.file = new File(dir, "store.json");
        this.backup = new File(dir, "store.json.bak");
        this.lockFile = new File(dir, "store.lock");
    }

    public File dir() {
        return dir;
    }

    /** Marca que cambia cada vez que se guarda algo (para detectar cambios desde otro proceso). */
    public String stamp() {
        return file.lastModified() + ":" + file.length();
    }

    // ------------------------------------------------------------------ lectura

    private static byte[] readAll(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                if (out.size() > 8 * 1024 * 1024) throw new IOException("Archivo demasiado grande");
            }
            return out.toByteArray();
        } finally {
            AddrUtil.closeQuietly(in);
        }
    }

    private static Data parseFile(File f) throws IOException {
        Object root;
        try {
            root = Json.parse(new String(readAll(f), StandardCharsets.UTF_8));
        } catch (Json.JsonException e) {
            throw new IOException("JSON inválido: " + e.getMessage());
        }
        Map<String, Object> m = Json.obj(root);
        if (m == null) throw new IOException("El archivo no tiene la forma esperada");
        Data d = new Data();
        List<Object> ps = Json.arr(m.get("proxies"));
        if (ps != null) {
            for (Object o : ps) {
                ProxyDef p = ProxyDef.fromMap(Json.obj(o));
                if (p != null && d.proxy(p.id) == null) d.proxies.add(p);
            }
        }
        List<Object> ss = Json.arr(m.get("sessions"));
        if (ss != null) {
            boolean[] slots = new boolean[SessionDef.MAX_SESSIONS];
            for (Object o : ss) {
                SessionDef s = SessionDef.fromMap(Json.obj(o));
                if (s == null || d.session(s.id) != null || slots[s.slot]) continue;
                if (s.proxyId != null && d.proxy(s.proxyId) == null) {
                    s.proxyId = null;
                    s.proxyEnabled = false;
                }
                slots[s.slot] = true;
                d.sessions.add(s);
            }
        }
        return d;
    }

    /** Lee el almacén. Nunca escribe en disco. Si está dañado, intenta con la copia de respaldo. */
    public Data read() {
        if (!file.exists()) {
            if (backup.exists()) {
                try {
                    Data d = parseFile(backup);
                    d.note = "Se recuperó la configuración desde la copia de respaldo.";
                    return d;
                } catch (IOException ignored) {
                    // sigue
                }
            }
            return new Data();
        }
        try {
            return parseFile(file);
        } catch (IOException first) {
            if (backup.exists()) {
                try {
                    Data d = parseFile(backup);
                    d.note = "El archivo de configuración estaba dañado; se recuperó la copia de respaldo.";
                    return d;
                } catch (IOException ignored) {
                    // sigue
                }
            }
            Data d = new Data();
            d.note = "El archivo de configuración estaba dañado y no se pudo recuperar. Se empezó de cero.";
            return d;
        }
    }

    // ------------------------------------------------------------------ escritura

    private void writeAtomic(Data d, boolean keepBackup) throws IOException {
        File tmp = new File(dir, "store.json.tmp");
        byte[] bytes = Json.stringify(d.toMap()).getBytes(StandardCharsets.UTF_8);
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(bytes);
            out.flush();
            out.getFD().sync();
        } finally {
            AddrUtil.closeQuietly(out);
        }
        if (file.exists()) {
            try {
                Files.copy(file.toPath(), (keepBackup ? backup : new File(dir, "store.json.corrupt")).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // la copia de respaldo es opcional
            }
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Aplica un cambio con el almacén bloqueado (también frente a otros procesos) y lo guarda. */
    public <T> T update(Mutation<T> m) throws IOException {
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) throw new IOException("No se pudo crear la carpeta de datos.");
        synchronized (JVM_LOCK) {
            RandomAccessFile raf = new RandomAccessFile(lockFile, "rw");
            try {
                FileChannel ch = raf.getChannel();
                FileLock lock = ch.lock();
                try {
                    Data d = read();
                    boolean recovering = d.note != null; // el archivo principal estaba dañado: no pisar la copia buena
                    T result = m.apply(d);
                    d.note = null;
                    writeAtomic(d, !recovering);
                    return result;
                } finally {
                    try {
                        lock.release();
                    } catch (IOException ignored) {
                        // nada
                    }
                }
            } finally {
                AddrUtil.closeQuietly(raf);
            }
        }
    }

    // ------------------------------------------------------------------ operaciones habituales

    public ProxyDef addProxy(final ProxyDef p) throws IOException {
        return update(new Mutation<ProxyDef>() {
            @Override
            public ProxyDef apply(Data d) throws IOException {
                ProxyDef c = p.copy();
                c.id = Validate.newId("p");
                d.proxies.add(c);
                return c;
            }
        });
    }

    public void saveProxy(final ProxyDef p) throws IOException {
        update(new Mutation<Void>() {
            @Override
            public Void apply(Data d) throws IOException {
                for (int i = 0; i < d.proxies.size(); i++) {
                    if (d.proxies.get(i).id.equals(p.id)) {
                        d.proxies.set(i, p.copy());
                        return null;
                    }
                }
                throw new IOException("Ese proxy ya no existe.");
            }
        });
    }

    /** Borra el proxy; las sesiones que lo usaban se quedan sin proxy (y con el interruptor apagado). */
    public int deleteProxy(final String id) throws IOException {
        return update(new Mutation<Integer>() {
            @Override
            public Integer apply(Data d) throws IOException {
                int affected = 0;
                for (SessionDef s : d.sessions) {
                    if (id.equals(s.proxyId)) {
                        s.proxyId = null;
                        s.proxyEnabled = false;
                        affected++;
                    }
                }
                for (int i = 0; i < d.proxies.size(); i++) {
                    if (d.proxies.get(i).id.equals(id)) {
                        d.proxies.remove(i);
                        break;
                    }
                }
                return Integer.valueOf(affected);
            }
        });
    }

    public SessionDef addSession(final SessionDef proto) throws IOException {
        return update(new Mutation<SessionDef>() {
            @Override
            public SessionDef apply(Data d) throws IOException {
                int slot = d.freeSlot();
                if (slot < 0) {
                    throw new IOException("Ya hay " + SessionDef.MAX_SESSIONS + " sesiones, que es el máximo. Elimina una para crear otra.");
                }
                SessionDef s = proto.copy();
                s.id = Validate.newId("s");
                s.slot = slot;
                s.color = proto.color;
                if (s.createdAt == 0) s.createdAt = System.currentTimeMillis();
                if (s.proxyId != null && d.proxy(s.proxyId) == null) {
                    s.proxyId = null;
                    s.proxyEnabled = false;
                }
                d.sessions.add(s);
                return s;
            }
        });
    }

    public void saveSession(final SessionDef s) throws IOException {
        update(new Mutation<Void>() {
            @Override
            public Void apply(Data d) throws IOException {
                for (int i = 0; i < d.sessions.size(); i++) {
                    SessionDef old = d.sessions.get(i);
                    if (old.id.equals(s.id)) {
                        SessionDef c = s.copy();
                        c.slot = old.slot; // el proceso de una sesión nunca cambia
                        c.createdAt = old.createdAt;
                        if (c.proxyId != null && d.proxy(c.proxyId) == null) {
                            c.proxyId = null;
                            c.proxyEnabled = false;
                        }
                        if (c.proxyId == null) c.proxyEnabled = false;
                        d.sessions.set(i, c);
                        return null;
                    }
                }
                throw new IOException("Esa sesión ya no existe.");
            }
        });
    }

    /** Cambia sólo el interruptor del proxy (lo usan el gestor y la propia sesión). */
    public SessionDef setProxyEnabled(final String sessionId, final boolean enabled) throws IOException {
        return update(new Mutation<SessionDef>() {
            @Override
            public SessionDef apply(Data d) throws IOException {
                SessionDef s = d.session(sessionId);
                if (s == null) throw new IOException("Esa sesión ya no existe.");
                if (enabled && (s.proxyId == null || d.proxy(s.proxyId) == null)) {
                    throw new IOException("Esta sesión no tiene un proxy elegido. Edítala y elige uno.");
                }
                s.proxyEnabled = enabled;
                return s.copy();
            }
        });
    }

    /** Copia sólo la configuración (nunca cookies ni historial). */
    public SessionDef duplicateSession(final String sessionId) throws IOException {
        return update(new Mutation<SessionDef>() {
            @Override
            public SessionDef apply(Data d) throws IOException {
                SessionDef src = d.session(sessionId);
                if (src == null) throw new IOException("Esa sesión ya no existe.");
                int slot = d.freeSlot();
                if (slot < 0) {
                    throw new IOException("Ya hay " + SessionDef.MAX_SESSIONS + " sesiones, que es el máximo. Elimina una para crear otra.");
                }
                SessionDef s = src.copy();
                s.id = Validate.newId("s");
                s.slot = slot;
                s.color = slot % SessionDef.COLORS.length;
                s.seed = new java.security.SecureRandom().nextInt() | 1; // otra huella aleatoria
                s.createdAt = System.currentTimeMillis();
                String base = src.name + " (copia)";
                if (base.length() > Validate.NAME_MAX) base = base.substring(0, Validate.NAME_MAX);
                s.name = base;
                d.sessions.add(s);
                return s;
            }
        });
    }

    public void deleteSession(final String sessionId) throws IOException {
        update(new Mutation<Void>() {
            @Override
            public Void apply(Data d) throws IOException {
                for (int i = 0; i < d.sessions.size(); i++) {
                    if (d.sessions.get(i).id.equals(sessionId)) {
                        d.sessions.remove(i);
                        return null;
                    }
                }
                return null;
            }
        });
    }
}
