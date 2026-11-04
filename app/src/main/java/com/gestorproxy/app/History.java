package com.gestorproxy.app;

import com.gestorproxy.core.AddrUtil;
import com.gestorproxy.core.Json;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Historial de una sesión: un archivo de texto (una línea por página), hasta 500 entradas. Sólo lo usa su proceso. */
final class History {
    static final int MAX = 500;

    static final class Entry {
        final long time;
        final String url;
        final String title;

        Entry(long time, String url, String title) {
            this.time = time;
            this.url = url;
            this.title = title;
        }
    }

    private final File file;

    History(File file) {
        this.file = file;
    }

    synchronized void add(String url, String title) {
        if (url == null || url.isEmpty()) return;
        List<Entry> all = readAll();
        if (!all.isEmpty() && all.get(all.size() - 1).url.equals(url)) {
            return; // la misma página seguida: no se repite
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("t", Long.valueOf(System.currentTimeMillis()));
        m.put("u", url.length() > 2048 ? url.substring(0, 2048) : url);
        m.put("n", title == null ? "" : (title.length() > 200 ? title.substring(0, 200) : title));
        String line = Json.stringify(m) + "\n";
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file, true);
            out.write(line.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            return;
        } finally {
            AddrUtil.closeQuietly(out);
        }
        if (all.size() + 1 > MAX + 100) rewrite(tail(all, MAX - 1), m);
    }

    private static List<Entry> tail(List<Entry> all, int n) {
        return new ArrayList<Entry>(all.subList(Math.max(0, all.size() - n), all.size()));
    }

    private void rewrite(List<Entry> keep, Map<String, Object> last) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : keep) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("t", Long.valueOf(e.time));
            m.put("u", e.url);
            m.put("n", e.title);
            sb.append(Json.stringify(m)).append('\n');
        }
        sb.append(Json.stringify(last)).append('\n');
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file, false);
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // nada
        } finally {
            AddrUtil.closeQuietly(out);
        }
    }

    private List<Entry> readAll() {
        List<Entry> out = new ArrayList<Entry>();
        if (!file.exists()) return out;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                try {
                    Map<String, Object> m = Json.obj(Json.parse(line));
                    if (m == null) continue;
                    String u = Json.str(m.get("u"), "");
                    if (u.isEmpty()) continue;
                    out.add(new Entry(Json.lng(m.get("t"), 0), u, Json.str(m.get("n"), "")));
                } catch (Json.JsonException ignored) {
                    // línea dañada: se salta
                }
            }
        } catch (IOException ignored) {
            // nada
        } finally {
            AddrUtil.closeQuietly(r);
        }
        return out;
    }

    /** Las últimas entradas, la más reciente primero. */
    synchronized List<Entry> recent(int max) {
        List<Entry> all = readAll();
        List<Entry> out = new ArrayList<Entry>();
        for (int i = all.size() - 1; i >= 0 && out.size() < max; i--) out.add(all.get(i));
        return out;
    }

    synchronized void clear() {
        file.delete();
    }
}
