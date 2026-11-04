package com.gestorproxy.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import com.gestorproxy.core.Bridge;
import com.gestorproxy.core.ProxyDef;
import com.gestorproxy.core.ProxyTester;
import com.gestorproxy.core.SessionDef;
import com.gestorproxy.core.Store;
import com.gestorproxy.core.TrackerList;
import com.gestorproxy.core.Upstream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Pantalla principal: sesiones, proxies y ayuda. */
public class MainActivity extends Activity {
    private static final String VERSION = "0.1.0 (versión de prueba)";

    private Ui ui;
    private Store store;
    private Store.Data data = new Store.Data();
    private final Map<String, Boolean> alive = new HashMap<String, Boolean>();
    private int tab = 0;
    private LinearLayout content;
    private ScrollView scrollView;
    private LinearLayout tabBar;
    private Button action;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();
    private boolean resumed;
    private boolean destroyed;
    private boolean noteShown;
    private String shownStamp = "";

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        ui = new Ui(this);
        store = AppFiles.store(this);
        getWindow().setStatusBarColor(ui.bg);
        getWindow().setNavigationBarColor(ui.bg);
        getWindow().getDecorView().setSystemUiVisibility(
                ui.night ? 0 : (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));
        buildFrame();
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        refresh();
        main.post(poll);
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    AppFiles.cleanOrphans(MainActivity.this, store.read());
                } catch (RuntimeException ignored) {
                    // no es crítico
                }
            }
        });
    }

    @Override
    protected void onPause() {
        resumed = false;
        main.removeCallbacks(poll);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        io.shutdown();
        super.onDestroy();
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!resumed || destroyed) return;
            io.execute(new Runnable() {
                @Override
                public void run() {
                    final String stamp = store.stamp();
                    final Map<String, Boolean> now = new HashMap<String, Boolean>();
                    for (SessionDef s : store.read().sessions) now.put(s.id, AppFiles.isSessionAlive(MainActivity.this, s.id));
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed || !resumed) return;
                            if (!stamp.equals(shownStamp) || !now.equals(alive)) {
                                alive.clear();
                                alive.putAll(now);
                                refresh();
                            }
                        }
                    });
                }
            });
            main.postDelayed(this, 1500);
        }
    };

    // ------------------------------------------------------------------ estructura

    private void buildFrame() {
        LinearLayout root = ui.vertical();
        root.setBackgroundColor(ui.bg);

        LinearLayout header = ui.vertical();
        header.setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(10));
        header.addView(ui.label("Gestor Proxy", 24, ui.text, true));
        header.addView(ui.dim("Ventanas de navegación separadas, cada una con su proxy"));
        root.addView(header);

        tabBar = ui.horizontal();
        tabBar.setPadding(ui.dp(12), 0, ui.dp(12), ui.dp(8));
        String[] names = {"Sesiones", "Proxies", "Ayuda"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = ui.label(names[i], 14, ui.text, true);
            t.setGravity(android.view.Gravity.CENTER);
            t.setPadding(ui.dp(8), ui.dp(10), ui.dp(8), ui.dp(10));
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(idx);
                }
            });
            tabBar.addView(t, ui.lpMargins(0, ViewGroup.LayoutParams.WRAP_CONTENT, 4, 0, 4, 0));
            ((LinearLayout.LayoutParams) t.getLayoutParams()).weight = 1f;
        }
        root.addView(tabBar);

        scrollView = new ScrollView(this);
        content = ui.vertical();
        content.setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(16));
        scrollView.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scrollView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottom = ui.vertical();
        bottom.setBackgroundColor(ui.bg);
        bottom.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(12));
        action = ui.button("", Ui.PRIMARY, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onAction();
            }
        });
        bottom.addView(action, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(bottom);

        setContentView(root);
    }

    private void selectTab(int i) {
        tab = i;
        scrollView.scrollTo(0, 0);
        render();
    }

    private void paintTabs() {
        for (int i = 0; i < tabBar.getChildCount(); i++) {
            TextView t = (TextView) tabBar.getChildAt(i);
            boolean sel = i == tab;
            t.setTextColor(sel ? ui.onPrimary : ui.textDim);
            t.setBackground(ui.ripple(ui.rounded(sel ? ui.primary : 0x00000000, 10)));
        }
    }

    private void onAction() {
        if (tab == 0) {
            if (data.freeSlot() < 0) {
                toast("Ya hay " + SessionDef.MAX_SESSIONS + " sesiones, que es el máximo. Elimina una para crear otra.");
                return;
            }
            Forms.sessionForm(this, ui, null, data);
        } else if (tab == 1) {
            Forms.proxyForm(this, ui, null);
        }
    }

    void toast(String msg) {
        if (!destroyed) Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    /** Relee el almacén y vuelve a dibujar la pantalla. */
    void refresh() {
        data = store.read();
        shownStamp = store.stamp();
        for (SessionDef s : data.sessions) {
            if (!alive.containsKey(s.id)) alive.put(s.id, Boolean.FALSE);
        }
        if (data.note != null && !noteShown) {
            noteShown = true;
            toast(data.note);
        }
        final int y = scrollView.getScrollY();
        render();
        scrollView.post(new Runnable() {
            @Override
            public void run() {
                scrollView.scrollTo(0, y);
            }
        });
    }

    private void render() {
        content.removeAllViews();
        paintTabs();
        switch (tab) {
            case 0:
                renderSessions();
                break;
            case 1:
                renderProxies();
                break;
            default:
                renderHelp();
                break;
        }
        action.setVisibility(tab == 2 ? View.GONE : View.VISIBLE);
        action.setText(tab == 0 ? "+  Nueva sesión" : "+  Añadir proxy");
    }

    private LinearLayout.LayoutParams cardParams() {
        return ui.lpMargins(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 4);
    }

    private LinearLayout.LayoutParams btnParams(boolean first) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(first ? 0 : ui.dp(8), 0, 0, 0);
        return p;
    }

    // ------------------------------------------------------------------ pestaña «Sesiones»

    private void renderSessions() {
        String crash = CrashLog.read(this);
        if (crash != null) content.addView(crashCard(crash), cardParams());

        if (data.sessions.isEmpty()) {
            LinearLayout c = ui.card();
            c.addView(ui.title("Aún no tienes sesiones"));
            c.addView(ui.space(6));
            c.addView(ui.body("Una sesión es una ventana de navegación aparte: tiene sus propias cookies, su propio historial "
                    + "y su propio proxy. Lo que haces en una no se mezcla con las demás."));
            c.addView(ui.space(8));
            c.addView(ui.dim(data.proxies.isEmpty()
                    ? "Para empezar: 1) añade tu proxy en la pestaña «Proxies»; 2) crea una sesión y elígelo; 3) pulsa «Abrir»."
                    : "Crea una sesión con el botón de abajo y elige uno de tus proxies."));
            content.addView(c, cardParams());
            return;
        }
        for (SessionDef s : data.sessions) content.addView(sessionCard(s), cardParams());
    }

    private View crashCard(final String crash) {
        LinearLayout c = ui.card();
        c.setBackground(ui.outlined(ui.night ? 0xFF3A2F12 : 0xFFFFF4CC, ui.warn, 14));
        c.addView(ui.label("La app tuvo un error la última vez", 15, ui.text, true));
        c.addView(ui.space(4));
        c.addView(ui.dim("Si quieres que te ayuden a arreglarlo, copia el detalle y envíalo."));
        c.addView(ui.space(8));
        LinearLayout row = ui.horizontal();
        row.addView(ui.button("Ver detalle", Ui.TONAL, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showText("Detalle del error", crash);
            }
        }), btnParams(true));
        row.addView(ui.button("Descartar", Ui.TONAL, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CrashLog.clear(MainActivity.this);
                refresh();
            }
        }), btnParams(false));
        c.addView(row);
        return c;
    }

    private View sessionCard(final SessionDef s) {
        final boolean isAlive = Boolean.TRUE.equals(alive.get(s.id));
        final Route r = Route.preview(s, data);
        LinearLayout card = ui.card();

        LinearLayout head = ui.horizontal();
        head.addView(ui.dot(s.colorValue(), 14));
        TextView nm = ui.title(s.name);
        nm.setPadding(ui.dp(10), 0, ui.dp(8), 0);
        nm.setSingleLine(true);
        nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(nm, ui.lpWeight(1f));
        if (isAlive) head.addView(ui.chip("ABIERTA", 0xFF15803D, Color.WHITE));
        card.addView(head);

        card.addView(ui.space(6));
        card.addView(ui.dim(r.proxy == null ? "Sin proxy elegido" : "Proxy: " + r.proxy.name + "  ·  " + r.proxy.describe()));
        int stColor;
        String stText;
        switch (r.mode) {
            case PROXY:
                stColor = ui.success;
                stText = "Proxy ENCENDIDO: la conexión sale por el proxy";
                break;
            case DIRECT:
                stColor = ui.warn;
                stText = "Proxy APAGADO: sale directo, con tu conexión real";
                break;
            default:
                stColor = ui.danger;
                stText = "Sin conexión: " + r.reason;
                break;
        }
        TextView st = ui.label(stText, 13, stColor, true);
        st.setPadding(0, ui.dp(4), 0, 0);
        card.addView(st);

        LinearLayout swRow = ui.horizontal();
        swRow.setPadding(0, ui.dp(8), 0, ui.dp(4));
        swRow.addView(ui.body("Proxy"), ui.lpWeight(1f));
        final Switch sw = new Switch(this);
        sw.setChecked(s.proxyEnabled);
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                if (checked == s.proxyEnabled) return;
                setProxyEnabled(s, checked);
            }
        });
        swRow.addView(sw);
        card.addView(swRow);

        LinearLayout btns = ui.horizontal();
        btns.addView(ui.button(isAlive ? "Ir a la sesión" : "Abrir", Ui.PRIMARY, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSession(s);
            }
        }), btnParams(true));
        btns.addView(ui.button("Probar", Ui.TONAL, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testSessionProxy(s);
            }
        }), btnParams(false));
        btns.addView(ui.button("Más", Ui.TONAL, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sessionMenu(v, s, isAlive);
            }
        }), btnParams(false));
        card.addView(btns);
        return card;
    }

    private void sessionMenu(View anchor, final SessionDef s, boolean isAlive) {
        PopupMenu m = new PopupMenu(this, anchor);
        Menu menu = m.getMenu();
        menu.add(0, 1, 0, "Editar");
        menu.add(0, 2, 1, "Duplicar (sólo la configuración)");
        menu.add(0, 3, 2, "Borrar cookies, datos e historial");
        menu.add(0, 4, 3, "Eliminar sesión");
        if (isAlive) menu.add(0, 5, 4, "Cerrar la sesión abierta");
        m.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(android.view.MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        Forms.sessionForm(MainActivity.this, ui, s, data);
                        break;
                    case 2:
                        duplicateSession(s);
                        break;
                    case 3:
                        wipeSession(s);
                        break;
                    case 4:
                        deleteSession(s);
                        break;
                    case 5:
                        AppFiles.requestClose(MainActivity.this, s.id);
                        toast("Cerrando «" + s.name + "»…");
                        break;
                    default:
                        break;
                }
                return true;
            }
        });
        m.show();
    }

    // ------------------------------------------------------------------ acciones sobre sesiones

    private void openSession(SessionDef s) {
        Intent i = new Intent(this, SessionActivity.classForSlot(s.slot));
        i.putExtra(SessionActivity.EXTRA_SID, s.id);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    private void setProxyEnabled(final SessionDef s, final boolean enabled) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    store.setProxyEnabled(s.id, enabled);
                } catch (IOException e) {
                    err = e.getMessage();
                }
                final String msg = err;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (msg != null) toast(msg);
                        refresh();
                    }
                });
            }
        });
    }

    /** Guarda una sesión (nueva o editada). Lo llama el formulario. */
    void saveSession(final SessionDef s, final boolean isNew) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    if (isNew) store.addSession(s);
                    else store.saveSession(s);
                } catch (IOException e) {
                    err = e.getMessage();
                }
                final String msg = err;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        toast(msg != null ? msg : (isNew ? "Sesión creada." : "Cambios guardados."));
                        refresh();
                    }
                });
            }
        });
    }

    private void duplicateSession(final SessionDef s) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    store.duplicateSession(s.id);
                } catch (IOException e) {
                    err = e.getMessage();
                }
                final String msg = err;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        toast(msg != null ? msg : "Sesión duplicada. La copia empieza sin cookies ni historial.");
                        refresh();
                    }
                });
            }
        });
    }

    /** Si la sesión está abierta, pide cerrarla y espera; luego ejecuta «after» (en el hilo principal). */
    private void withSessionClosed(final SessionDef s, final String what, final Runnable after) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                final boolean open = AppFiles.isSessionAlive(MainActivity.this, s.id);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (destroyed) return;
                        if (!open) {
                            after.run();
                            return;
                        }
                        Ui.confirm(MainActivity.this, "La sesión «" + s.name + "» está abierta",
                                "Para " + what + " hay que cerrarla primero. ¿La cierro ahora?", "Cerrar y seguir", new Runnable() {
                                    @Override
                                    public void run() {
                                        closeAndWait(s, after);
                                    }
                                });
                    }
                });
            }
        });
    }

    private void closeAndWait(final SessionDef s, final Runnable after) {
        AppFiles.requestClose(this, s.id);
        io.execute(new Runnable() {
            @Override
            public void run() {
                boolean closed = false;
                for (int i = 0; i < 50 && !closed; i++) {
                    if (!AppFiles.isSessionAlive(MainActivity.this, s.id)) {
                        closed = true;
                        break;
                    }
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                final boolean ok = closed;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (destroyed) return;
                        if (ok) after.run();
                        else toast("No se pudo cerrar la sesión. Ciérrala desde su propia ventana y vuelve a intentarlo.");
                    }
                });
            }
        });
    }

    private void wipeSession(final SessionDef s) {
        Ui.confirm(this, "Borrar los datos de «" + s.name + "»",
                "Se borrarán sus cookies, su almacenamiento, su caché y su historial. La sesión y su configuración se conservan.",
                "Borrar datos", new Runnable() {
                    @Override
                    public void run() {
                        withSessionClosed(s, "borrar sus datos", new Runnable() {
                            @Override
                            public void run() {
                                io.execute(new Runnable() {
                                    @Override
                                    public void run() {
                                        final boolean clean = AppFiles.wipeSessionData(MainActivity.this, s.id);
                                        main.post(new Runnable() {
                                            @Override
                                            public void run() {
                                                toast(clean ? "Datos de «" + s.name + "» borrados."
                                                        : "Se borró casi todo, pero quedaron restos. Vuelve a intentarlo con la sesión cerrada.");
                                                refresh();
                                            }
                                        });
                                    }
                                });
                            }
                        });
                    }
                });
    }

    private void deleteSession(final SessionDef s) {
        Ui.confirm(this, "Eliminar «" + s.name + "»",
                "Se eliminará la sesión y todos sus datos (cookies, almacenamiento, caché e historial). No se puede deshacer.",
                "Eliminar", new Runnable() {
                    @Override
                    public void run() {
                        withSessionClosed(s, "eliminarla", new Runnable() {
                            @Override
                            public void run() {
                                io.execute(new Runnable() {
                                    @Override
                                    public void run() {
                                        AppFiles.wipeSessionData(MainActivity.this, s.id);
                                        String err = null;
                                        try {
                                            store.deleteSession(s.id);
                                        } catch (IOException e) {
                                            err = e.getMessage();
                                        }
                                        if (err == null) AppFiles.deleteSessionFolder(MainActivity.this, s.id);
                                        final String msg = err;
                                        main.post(new Runnable() {
                                            @Override
                                            public void run() {
                                                toast(msg != null ? msg : "Sesión eliminada.");
                                                refresh();
                                            }
                                        });
                                    }
                                });
                            }
                        });
                    }
                });
    }

    private void testSessionProxy(SessionDef s) {
        ProxyDef p = s.proxyId == null ? null : data.proxy(s.proxyId);
        if (p == null) {
            Ui.info(this, "Probar conexión", "Esta sesión no tiene un proxy elegido. Edítala y elige uno.");
            return;
        }
        testProxy(p);
    }

    // ------------------------------------------------------------------ pestaña «Proxies»

    private void renderProxies() {
        if (data.proxies.isEmpty()) {
            LinearLayout c = ui.card();
            c.addView(ui.title("Aún no tienes proxies"));
            c.addView(ui.space(6));
            c.addView(ui.body("Añade el proxy que te dio tu proveedor: de tipo HTTP, HTTPS o SOCKS5, con usuario y contraseña si los necesita."));
            c.addView(ui.space(8));
            c.addView(ui.dim("Puedes pegarlo completo, por ejemplo usuario:clave@servidor:puerto."));
            content.addView(c, cardParams());
            return;
        }
        for (ProxyDef p : data.proxies) content.addView(proxyCard(p), cardParams());
    }

    private View proxyCard(final ProxyDef p) {
        LinearLayout card = ui.card();
        card.addView(ui.title(p.name));
        card.addView(ui.space(4));
        card.addView(ui.mono(p.describe()));
        String auth;
        if (p.username.isEmpty()) auth = "Sin usuario";
        else if (!p.hasPassword()) auth = "Usuario: " + p.username + " (sin contraseña)";
        else if (SecretBox.isWeak(p.passwordEnc))
            auth = "Usuario: " + p.username + " · contraseña guardada SIN cifrar (este teléfono no permitió cifrarla)";
        else auth = "Usuario: " + p.username + " · contraseña guardada cifrada en este teléfono";
        card.addView(ui.dim(auth));
        int used = data.sessionsUsing(p.id);
        card.addView(ui.dim(used == 0 ? "No la usa ninguna sesión" : "La usan " + used + (used == 1 ? " sesión" : " sesiones")));
        card.addView(ui.space(8));
        LinearLayout btns = ui.horizontal();
        btns.addView(ui.button("Probar", Ui.PRIMARY, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testProxy(p);
            }
        }), btnParams(true));
        btns.addView(ui.button("Editar", Ui.TONAL, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Forms.proxyForm(MainActivity.this, ui, p);
            }
        }), btnParams(false));
        btns.addView(ui.button("Eliminar", Ui.DANGER, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteProxy(p);
            }
        }), btnParams(false));
        card.addView(btns);
        return card;
    }

    /** Guarda un proxy (nuevo o editado). Lo llama el formulario. */
    void saveProxy(final ProxyDef p, final boolean isNew) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    if (isNew) store.addProxy(p);
                    else store.saveProxy(p);
                } catch (IOException e) {
                    err = e.getMessage();
                }
                final String msg = err;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        toast(msg != null ? msg : (isNew ? "Proxy añadido." : "Cambios guardados."));
                        refresh();
                    }
                });
            }
        });
    }

    private void deleteProxy(final ProxyDef p) {
        int used = data.sessionsUsing(p.id);
        String extra = used == 0 ? "" : "\n\nLas " + used + " sesiones que lo usan se quedarán sin proxy y con el interruptor apagado.";
        Ui.confirm(this, "Eliminar «" + p.name + "»", "Se borra el proxy y su contraseña guardada." + extra, "Eliminar", new Runnable() {
            @Override
            public void run() {
                io.execute(new Runnable() {
                    @Override
                    public void run() {
                        String err = null;
                        try {
                            store.deleteProxy(p.id);
                        } catch (IOException e) {
                            err = e.getMessage();
                        }
                        final String msg = err;
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                toast(msg != null ? msg : "Proxy eliminado.");
                                refresh();
                            }
                        });
                    }
                });
            }
        });
    }

    private void testProxy(ProxyDef p) {
        try {
            runTest(AppFiles.upstreamFor(p), p.name + "  (" + p.describe() + ")");
        } catch (IllegalStateException e) {
            Ui.info(this, "Probar conexión", e.getMessage());
        }
    }

    /** Prueba un proxy (si funciona, IP pública, país, velocidad y errores) y muestra el resultado. */
    void runTest(final Upstream up, final String title) {
        final AlertDialog[] dlg = new AlertDialog[1];
        final boolean[] cancelled = new boolean[1];
        dlg[0] = new AlertDialog.Builder(this)
                .setTitle("Probando…")
                .setMessage("Se compara tu IP real con la que ve el mundo a través del proxy. Puede tardar hasta unos 30 segundos.")
                .setNegativeButton("Cancelar", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        cancelled[0] = true;
                    }
                })
                .setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(android.content.DialogInterface d) {
                        cancelled[0] = true;
                    }
                })
                .create();
        dlg[0].show();
        io.execute(new Runnable() {
            @Override
            public void run() {
                String out;
                try {
                    out = ProxyTester.describe(ProxyTester.run(up, true, new ProxyTester.Options()));
                } catch (RuntimeException e) {
                    out = "No se pudo hacer la prueba: " + e.getMessage();
                }
                final String text = out;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (cancelled[0] || destroyed) return;
                        dlg[0].dismiss();
                        showText("Resultado: " + title, text);
                    }
                });
            }
        });
    }

    private void showText(String title, final String text) {
        if (destroyed || isFinishing()) return;
        LinearLayout box = ui.vertical();
        box.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8));
        TextView t = ui.mono(text);
        box.addView(t);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(ui.scroll(box))
                .setPositiveButton("Cerrar", null)
                .setNeutralButton("Copiar", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("detalle", text));
                            toast("Copiado.");
                        }
                    }
                })
                .show();
    }

    // ------------------------------------------------------------------ pestaña «Ayuda»

    private View helpCard(String title, String... lines) {
        LinearLayout c = ui.card();
        c.addView(ui.title(title));
        for (String l : lines) {
            c.addView(ui.space(6));
            c.addView(ui.body(l));
        }
        return c;
    }

    private void renderHelp() {
        content.addView(helpCard("¿Qué es esto?",
                "Gestor Proxy abre páginas web en «sesiones» separadas. Cada sesión es como un navegador aparte: tiene sus propias "
                        + "cookies, su propio almacenamiento, su propia caché, su propio historial, sus permisos y su propio proxy. "
                        + "Lo que pasa en una sesión no se mezcla con las otras.",
                "No cambia la conexión del teléfono: sólo lo que se abre dentro de las sesiones sale por el proxy elegido."),
                cardParams());
        content.addView(helpCard("Cómo empezar",
                "1. En «Proxies» añade tu proxy (HTTP, HTTPS o SOCKS5). Si lo necesita, escribe usuario y contraseña. "
                        + "Puedes pegarlo completo, por ejemplo usuario:clave@servidor:puerto.",
                "2. En «Sesiones» crea una sesión y elige ese proxy.",
                "3. Pulsa «Abrir». Arriba verás el color de la sesión y por dónde sale la conexión. "
                        + "El interruptor «Proxy» lo enciende o lo apaga.",
                "4. Usa «Probar» para comprobar que el proxy funciona, ver su IP pública y compararla con tu IP real."),
                cardParams());
        content.addView(helpCard("Lo que sí hace",
                "• Cada sesión sale por su proxy. Si el proxy falla, la sesión NO se conecta directo: verás un error. "
                        + "No hay «plan B» que muestre tu IP real.",
                "• Con el proxy apagado, la sesión sale directo (con tu IP real) o se bloquea, según lo que elijas al crearla. "
                        + "La barra de arriba siempre te dice cuál es el caso.",
                "• Los nombres de las páginas se resuelven en el proxy y no en tu teléfono, para evitar fugas de DNS.",
                "• Bloquea rastreadores conocidos (" + TrackerList.size() + " reglas), las cookies de terceros (opcional) y WebRTC, "
                        + "y reduce algunos datos que sirven para identificar tu navegador.",
                "• No deja que las páginas abran direcciones de tu propia red (router, impresoras, otros aparatos).",
                "• Las contraseñas de los proxies se guardan cifradas con una clave que no sale del teléfono."),
                cardParams());
        content.addView(helpCard("Lo que NO hace (léelo)",
                "• No te hace anónimo ni te protege de todo tipo de rastreo. Los sitios aún pueden reconocerte por tu cuenta, tu "
                        + "comportamiento, tu zona horaria, tu idioma y otras señales. La reducción de huella digital ayuda, pero no es perfecta.",
                "• Tu proveedor de proxy puede ver a qué sitios entras (y, en páginas sin HTTPS, lo que envías). Un proxy no cifra "
                        + "por sí solo tu conexión.",
                "• Android no permite aislar todo: el motor web del sistema («Android System WebView») puede hacer conexiones "
                        + "propias (por ejemplo, actualizaciones) fuera del proxy.",
                "• El puente local que usa cada sesión escucha sólo dentro del teléfono (127.0.0.1) pero no tiene contraseña: "
                        + "otra app del mismo teléfono podría usarlo mientras la sesión esté abierta.",
                "• Máximo " + SessionDef.MAX_SESSIONS + " sesiones (cada una usa su propio proceso de Android).",
                "• En esta versión no hay descargas, subida de archivos ni extensiones.",
                "• Necesita Android 9 o más y un WebView reciente. Si el teléfono no lo permite, la sesión no abre nada (por seguridad).",
                "• Es una versión de prueba: puede fallar en algunos teléfonos."),
                cardParams());

        LinearLayout about = ui.card();
        about.addView(ui.title("Versión"));
        about.addView(ui.space(4));
        about.addView(ui.dim("Gestor Proxy " + VERSION));
        String crash = CrashLog.read(this);
        if (crash != null) {
            about.addView(ui.space(8));
            about.addView(ui.button("Ver el último error", Ui.TONAL, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String c = CrashLog.read(MainActivity.this);
                    showText("Detalle del error", c == null ? "(vacío)" : c);
                }
            }));
        }
        content.addView(about, cardParams());
    }
}
