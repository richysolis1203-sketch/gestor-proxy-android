package com.gestorproxy.app;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import com.gestorproxy.core.AddrUtil;
import com.gestorproxy.core.Bridge;
import com.gestorproxy.core.Privacy;
import com.gestorproxy.core.ProxyDef;
import com.gestorproxy.core.ProxyTester;
import com.gestorproxy.core.SessionDef;
import com.gestorproxy.core.Store;
import com.gestorproxy.core.TrackerList;
import com.gestorproxy.core.Upstream;
import com.gestorproxy.core.Validate;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Una sesión abierta: su propia ventana, su propio proceso de Android y su propio navegador (WebView) con sus propios
 * datos. Hay 10 copias de esta clase (S0..S9), cada una declarada en el manifiesto con su propio proceso.
 *
 * <p>Todo el tráfico del navegador pasa SIEMPRE por el puente local (Bridge), que decide si sale por el proxy, directo
 * o bloqueado. Nada se carga hasta que el navegador ya apunta al puente.
 */
public class SessionActivity extends Activity {
    static final String EXTRA_SID = "sid";

    /** Dirección ficticia (el dominio .invalid nunca existe) con la que se sirven las páginas propias de la app. */
    private static final String INTERNAL_HOST = "gestor-proxy.invalid";

    private static final String START_URL = "https://" + INTERNAL_HOST + "/inicio";

    /** Sesión que usa este proceso. Un proceso sólo puede usar una carpeta de datos del navegador durante su vida. */
    private static String boundSid;

    private static boolean suffixApplied;

    static Class<? extends SessionActivity> classForSlot(int slot) {
        switch (slot) {
            case 0:
                return S0.class;
            case 1:
                return S1.class;
            case 2:
                return S2.class;
            case 3:
                return S3.class;
            case 4:
                return S4.class;
            case 5:
                return S5.class;
            case 6:
                return S6.class;
            case 7:
                return S7.class;
            case 8:
                return S8.class;
            case 9:
                return S9.class;
            default:
                throw new IllegalArgumentException("Proceso de sesión no válido: " + slot);
        }
    }

    private Ui ui;
    private Store store;
    private String sid;
    private volatile SessionDef session;
    private volatile Bridge bridge;
    private volatile Route route;
    private History history;
    private RandomAccessFile aliveFile;
    private FileLock aliveLock;
    private boolean aborted;
    private boolean destroyed;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Object cfgLock = new Object();
    private volatile String appliedStamp = "";
    private final Executor mainExecutor = new Executor() {
        @Override
        public void execute(Runnable r) {
            main.post(r);
        }
    };

    // vistas
    private FrameLayout webHost;
    private WebView web;
    private EditText omnibox;
    private ProgressBar progress;
    private Switch proxySwitch;
    private TextView modeChip;
    private TextView routeText;
    private TextView notice;
    private TextView btnBack;
    private TextView btnForward;
    private boolean updatingSwitch;

    // estado de la página
    private String errorShownFor;
    private String fallbackScript;
    private ScriptHandler scriptHandler;
    private long backAt;
    private final AtomicInteger blockedInPage = new AtomicInteger();

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        ui = new Ui(this);
        String id = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_SID);
        if (!Validate.isSessionId(id)) {
            abort("No se pudo abrir la sesión.");
            return;
        }
        if (boundSid != null && !boundSid.equals(id)) {
            // Este proceso ya sirvió a otra sesión y no puede cambiar de carpeta de datos: se reinicia limpio.
            aborted = true;
            Process.killProcess(Process.myPid());
            return;
        }
        sid = id;
        boundSid = id;
        store = AppFiles.store(this);
        SessionDef s = store.read().session(id);
        if (s == null) {
            abort("Esa sesión ya no existe.");
            return;
        }
        session = s;

        // Carpeta de datos propia del navegador: debe ser lo primero que se hace con WebView en este proceso.
        if (!suffixApplied) {
            try {
                WebView.setDataDirectorySuffix(SessionDef.webViewSuffix(id));
                suffixApplied = true;
            } catch (IllegalStateException e) {
                CrashLog.record(this, "No se pudo separar la carpeta de datos del navegador", e);
                abort("No se pudo aislar los datos de esta sesión. Ciérrala y ábrela de nuevo.");
                return;
            }
        }
        if (!takeAliveLock()) {
            abort("Esta sesión ya está abierta.");
            return;
        }
        AppFiles.clearCloseRequest(this, id);
        history = new History(AppFiles.historyFile(this, id));

        if (s.secureScreen) {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        }
        setTaskDescription(new ActivityManager.TaskDescription(s.name, null, s.colorValue()));
        getWindow().setStatusBarColor(s.colorValue());
        getWindow().getDecorView().setBackgroundColor(ui.bg);

        buildUi();
        startEngine();
        schedulePoll();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        if (store != null && !aborted) {
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    refreshConfig();
                }
            });
        }
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        try {
            if (suffixApplied && web != null) CookieManager.getInstance().flush();
        } catch (RuntimeException ignored) {
            // no es crítico
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        try {
            if (web != null) {
                webHost.removeAllViews();
                web.stopLoading();
                web.destroy();
                web = null;
            }
        } catch (RuntimeException ignored) {
            // nada
        }
        Bridge b = bridge;
        if (b != null) b.stop();
        worker.shutdownNow();
        if (!aborted || aliveLock != null) releaseAliveLock();
        super.onDestroy();
        if (isFinishing()) {
            // Termina el proceso: así la próxima sesión que use este proceso empieza con un navegador limpio.
            Process.killProcess(Process.myPid());
        }
    }

    private void abort(String msg) {
        aborted = true;
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        finishAndRemoveTask();
    }

    private void closeSession() {
        finishAndRemoveTask();
    }

    private boolean takeAliveLock() {
        // Se reintenta unas veces: el gestor toma el bloqueo un instante para saber si la sesión está abierta.
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                aliveFile = new RandomAccessFile(AppFiles.aliveLockFile(this, sid), "rw");
                aliveLock = aliveFile.getChannel().tryLock();
                if (aliveLock != null) return true;
            } catch (IOException e) {
                // se reintenta
            } catch (OverlappingFileLockException e) {
                // se reintenta
            }
            AddrUtil.closeQuietly(aliveFile);
            aliveFile = null;
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return false;
    }

    private void releaseAliveLock() {
        try {
            if (aliveLock != null) aliveLock.release();
        } catch (IOException ignored) {
            // nada
        }
        aliveLock = null;
        AddrUtil.closeQuietly(aliveFile);
        aliveFile = null;
    }

    // ------------------------------------------------------------------ interfaz

    private TextView navButton(String label, View.OnClickListener l) {
        TextView t = ui.label(label, 13, ui.text, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(12));
        t.setBackground(ui.ripple(ui.rounded(0x00000000, 0)));
        t.setOnClickListener(l);
        t.setLayoutParams(ui.lpWeight(1f));
        return t;
    }

    private void buildUi() {
        final int color = session.colorValue();
        LinearLayout root = ui.vertical();
        root.setBackgroundColor(ui.bg);

        // Barra de color: nombre, interruptor del proxy y por dónde sale la conexión.
        LinearLayout top = ui.vertical();
        top.setBackgroundColor(color);
        top.setPadding(ui.dp(14), ui.dp(10), ui.dp(12), ui.dp(8));
        LinearLayout row1 = ui.horizontal();
        TextView name = ui.label(session.name, 17, Color.WHITE, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        row1.addView(name, ui.lpWeight(1f));
        modeChip = ui.chip("", 0x33FFFFFF, Color.WHITE);
        row1.addView(modeChip, ui.lpMargins(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 8, 0, 8, 0));
        TextView swLabel = ui.label("Proxy", 13, Color.WHITE, true);
        row1.addView(swLabel);
        proxySwitch = new Switch(this);
        proxySwitch.setPadding(ui.dp(6), 0, 0, 0);
        row1.addView(proxySwitch);
        top.addView(row1);
        routeText = ui.label("Preparando…", 12, 0xE6FFFFFF, false);
        routeText.setSingleLine(true);
        routeText.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(routeText);
        root.addView(top, ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        proxySwitch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                if (!updatingSwitch) onSwitchChanged(checked);
            }
        });

        // Aviso (cambios pendientes, etc.)
        notice = ui.label("", 12, ui.text, false);
        notice.setBackgroundColor(night() ? 0xFF3A2F12 : 0xFFFFF4CC);
        notice.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8));
        notice.setVisibility(View.GONE);
        notice.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                notice.setVisibility(View.GONE);
            }
        });
        root.addView(notice, ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Barra de direcciones
        LinearLayout bar = ui.horizontal();
        bar.setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(6));
        omnibox = ui.field("Buscar o escribir una dirección",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        omnibox.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        omnibox.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        omnibox.setSelectAllOnFocus(true);
        omnibox.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent ev) {
                boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN;
                if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                    go();
                    return true;
                }
                return false;
            }
        });
        bar.addView(omnibox, ui.lpWeight(1f));
        android.widget.Button goBtn = ui.button("Ir", Ui.PRIMARY, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                go();
            }
        });
        bar.addView(goBtn, ui.lpMargins(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 8, 0, 0, 0));
        root.addView(bar, ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(color));
        progress.setVisibility(View.INVISIBLE);
        root.addView(progress, ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(3)));

        webHost = new FrameLayout(this);
        webHost.setBackgroundColor(ui.bg);
        root.addView(webHost, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(ui.divider());
        LinearLayout nav = ui.horizontal();
        nav.setBackgroundColor(ui.surface);
        btnBack = navButton("Atrás", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (web != null && web.canGoBack()) web.goBack();
            }
        });
        btnForward = navButton("Adelante", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (web != null && web.canGoForward()) web.goForward();
            }
        });
        nav.addView(btnBack);
        nav.addView(btnForward);
        nav.addView(navButton("Recargar", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                reloadPage();
            }
        }));
        nav.addView(navButton("Inicio", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showStartPage();
            }
        }));
        nav.addView(navButton("Más", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu(v);
            }
        }));
        root.addView(nav, ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        updateNav();
    }

    private boolean night() {
        return ui.night;
    }

    private void toast(String msg) {
        if (!destroyed) Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private void showNotice(String msg) {
        if (destroyed || notice == null) return;
        notice.setText(msg + "  (toca para ocultar)");
        notice.setVisibility(View.VISIBLE);
    }

    private void updateNav() {
        if (btnBack == null) return;
        boolean b = web != null && web.canGoBack();
        boolean f = web != null && web.canGoForward();
        btnBack.setAlpha(b ? 1f : 0.35f);
        btnForward.setAlpha(f ? 1f : 0.35f);
    }

    private void updateRouteViews() {
        Route r = route;
        if (r == null || routeText == null) return;
        routeText.setText(r.label());
        modeChip.setText(r.chipText());
        int chipFill;
        switch (r.mode) {
            case PROXY:
                chipFill = 0xFF15803D;
                break;
            case DIRECT:
                chipFill = 0xFFB45309;
                break;
            default:
                chipFill = 0xFFB91C1C;
                break;
        }
        modeChip.setBackground(ui.rounded(chipFill, 20));
        SessionDef s = session;
        updatingSwitch = true;
        proxySwitch.setChecked(s != null && s.proxyEnabled);
        updatingSwitch = false;
    }

    // ------------------------------------------------------------------ puente y proxy del navegador

    private void startEngine() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            routeText.setText("Sin conexión: el motor web de este teléfono no permite proxy por sesión.");
            showNotice("Actualiza «Android System WebView» desde Google Play y vuelve a abrir la sesión. "
                    + "Por seguridad, sin ese soporte no se abre ninguna página.");
            return;
        }
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Bridge b = new Bridge();
                    b.setFreshener(new Bridge.Freshener() {
                        @Override
                        public void beforeRequest() {
                            refreshConfig();
                        }
                    });
                    final int port = b.start();
                    bridge = b;
                    refreshConfig(); // el puente empieza BLOQUEADO y se abre sólo según la configuración
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            applyWebViewProxy(port);
                        }
                    });
                } catch (final IOException e) {
                    CrashLog.record(SessionActivity.this, "No se pudo iniciar el puente local", e);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            routeText.setText("Sin conexión: no se pudo iniciar el puente local.");
                            showNotice("No se pudo iniciar el puente local: " + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    private void applyWebViewProxy(int port) {
        if (destroyed) return;
        try {
            ProxyConfig cfg = new ProxyConfig.Builder()
                    .addProxyRule("http://127.0.0.1:" + port)
                    .removeImplicitRules()
                    .build();
            ProxyController.getInstance().setProxyOverride(cfg, mainExecutor, new Runnable() {
                @Override
                public void run() {
                    if (destroyed) return;
                    createWebView();
                    loadInitial();
                }
            });
        } catch (RuntimeException e) {
            CrashLog.record(this, "No se pudo dirigir el navegador al puente local", e);
            routeText.setText("Sin conexión: no se pudo dirigir el navegador al puente local.");
            showNotice("No se pudo dirigir el navegador al puente local. Por seguridad no se abre ninguna página.");
        }
    }

    /** Relee la configuración si cambió (desde el gestor o desde aquí) y ajusta el puente. Se puede llamar desde cualquier hilo. */
    private void refreshConfig() {
        String st = store.stamp();
        if (st.equals(appliedStamp)) return;
        synchronized (cfgLock) {
            st = store.stamp();
            if (st.equals(appliedStamp)) return;
            Bridge b = bridge;
            if (b == null) return;
            Store.Data d = store.read();
            final SessionDef s = d.session(sid);
            if (s == null) {
                b.configure(Bridge.Mode.BLOCKED, null, true, "Esta sesión fue eliminada.");
                appliedStamp = st;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!destroyed) closeSession();
                    }
                });
                return;
            }
            final Route r = Route.of(s, d);
            b.configure(r.mode, r.upstream, s.blockTrackers, r.reason);
            final SessionDef prev = session;
            session = s;
            final Route prevRoute = route;
            route = r;
            appliedStamp = st;
            main.post(new Runnable() {
                @Override
                public void run() {
                    onConfigChanged(prev, s, prevRoute, r);
                }
            });
        }
    }

    private void onConfigChanged(SessionDef prev, SessionDef s, Route prevRoute, Route r) {
        if (destroyed) return;
        updateRouteViews();
        if (web != null && prev != null && prev.blockThirdPartyCookies != s.blockThirdPartyCookies) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, !s.blockThirdPartyCookies);
        }
        if (prev != null
                && (prev.fingerprintProtection != s.fingerprintProtection
                        || prev.webrtcProtection != s.webrtcProtection
                        || prev.sendPrivacySignals != s.sendPrivacySignals
                        || prev.secureScreen != s.secureScreen
                        || prev.seed != s.seed)) {
            showNotice("Cambiaste ajustes de privacidad: se aplican por completo al cerrar esta sesión y abrirla de nuevo.");
        }
        if (prevRoute != null && (prevRoute.mode != r.mode || prevRoute.proxy != r.proxy)) {
            showNotice("Cambió la ruta de salida. Recarga la página para que use la conexión nueva.");
        }
    }

    private void onSwitchChanged(final boolean want) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    store.setProxyEnabled(sid, want);
                    refreshConfig();
                } catch (final IOException e) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            toast(e.getMessage());
                            updateRouteViews();
                        }
                    });
                }
            }
        });
    }

    private void schedulePoll() {
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (destroyed) return;
                try {
                    worker.execute(new Runnable() {
                        @Override
                        public void run() {
                            refreshConfig();
                            if (AppFiles.closeRequested(SessionActivity.this, sid)) {
                                main.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (!destroyed) closeSession();
                                    }
                                });
                            }
                        }
                    });
                } catch (RuntimeException ignored) {
                    return; // ya se está cerrando
                }
                schedulePoll();
            }
        }, 1200);
    }

    // ------------------------------------------------------------------ navegador

    private String readAsset(String name) {
        InputStream in = null;
        try {
            in = getAssets().open(name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        } finally {
            AddrUtil.closeQuietly(in);
        }
    }

    private void createWebView() {
        final SessionDef s = session;
        WebView w = new WebView(this);
        w.setBackgroundColor(ui.bg);
        w.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        WebSettings st = w.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setDatabaseEnabled(true);
        st.setAllowFileAccess(false);
        st.setAllowContentAccess(false);
        st.setAllowFileAccessFromFileURLs(false);
        st.setAllowUniversalAccessFromFileURLs(false);
        st.setGeolocationEnabled(false);
        st.setSupportMultipleWindows(false);
        st.setJavaScriptCanOpenWindowsAutomatically(false);
        st.setMediaPlaybackRequiresUserGesture(true);
        st.setSaveFormData(false);
        st.setSafeBrowsingEnabled(false);
        st.setBuiltInZoomControls(true);
        st.setDisplayZoomControls(false);
        st.setSupportZoom(true);
        st.setUseWideViewPort(true);
        st.setLoadWithOverviewMode(true);
        if (s.fingerprintProtection) {
            st.setUserAgentString(Privacy.reduceUserAgent(st.getUserAgentString()));
        }

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(w, !s.blockThirdPartyCookies);

        w.setWebViewClient(new Client());
        w.setWebChromeClient(new Chrome());
        w.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String ua, String cd, String mime, long len) {
                toast("Las descargas no están disponibles en esta versión (se bloquean para no salir del aislamiento).");
            }
        });

        // Protecciones que se ejecutan al inicio de cada página (huella digital, WebRTC, señales de privacidad).
        String gp = Privacy.needsContentScript(s) ? readAsset("gp-protect.js") : "";
        String rtc = s.webrtcProtection ? readAsset("gp-webrtc.js") : "";
        String script = Privacy.buildScript(gp, rtc, s);
        if (Privacy.needsContentScript(s) && gp.isEmpty()) showNotice("No se pudo cargar la protección contra huella digital.");
        if (s.webrtcProtection && rtc.isEmpty()) showNotice("No se pudo cargar el bloqueo de WebRTC.");
        if (!script.isEmpty()) {
            boolean installed = false;
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                try {
                    scriptHandler = WebViewCompat.addDocumentStartJavaScript(w, script, Collections.singleton("*"));
                    installed = true;
                } catch (RuntimeException e) {
                    CrashLog.record(this, "No se pudo instalar el script de inicio", e);
                }
            }
            if (!installed) {
                fallbackScript = script;
                showNotice("Tu WebView es antiguo: las protecciones de huella y WebRTC se aplican tarde y pueden fallar. "
                        + "Actualiza «Android System WebView».");
            }
        }

        webHost.removeAllViews();
        webHost.addView(w, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web = w;
        updateNav();
    }

    private void loadInitial() {
        SessionDef s = session;
        if (s.startUrl != null && Validate.isWebUrl(s.startUrl)) {
            openUrl(s.startUrl);
        } else {
            showStartPage();
        }
    }

    private void openUrl(String url) {
        if (web == null) return;
        errorShownFor = null;
        web.loadUrl(url);
    }

    private void showStartPage() {
        if (web == null) return;
        errorShownFor = null;
        web.loadDataWithBaseURL(START_URL, Privacy.startPageHtml(session.name), "text/html", "utf-8", START_URL);
        omnibox.setText("");
    }

    private void go() {
        Validate.UrlResult r = Validate.normalizeUserUrl(omnibox.getText().toString());
        if (!r.ok) {
            toast(r.error);
            return;
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(omnibox.getWindowToken(), 0);
        omnibox.clearFocus();
        openUrl(r.url);
    }

    private void reloadPage() {
        if (web == null) return;
        String shown = web.getUrl();
        if (errorShownFor != null && errorShownFor.equals(shown)) {
            openUrl(errorShownFor);
        } else if (START_URL.equals(shown)) {
            showStartPage();
        } else {
            web.reload();
        }
    }

    private static boolean isInternal(String url) {
        return url == null || url.isEmpty() || url.equals(START_URL) || url.startsWith("about:") || url.startsWith("data:");
    }

    private void showLoadError(String failedUrl, int code, String description) {
        if (web == null || destroyed) return;
        Bridge b = bridge;
        Bridge.Config c = b == null ? null : b.config();
        String host = null;
        try {
            host = Uri.parse(failedUrl).getHost();
        } catch (RuntimeException ignored) {
            // nada
        }
        String detail;
        if (c != null && c.mode == Bridge.Mode.BLOCKED) {
            detail = "Esta sesión no tiene salida a internet ahora mismo. " + c.reason;
        } else if (host != null && session.blockTrackers && TrackerList.isTrackerHost(host)) {
            detail = "Esta dirección es de un rastreador conocido y la sesión la bloquea. "
                    + "Si de verdad quieres abrirla, apaga «Bloquear rastreadores» en los ajustes de la sesión.";
        } else if (b != null && b.lastErrorAt() > 0 && System.currentTimeMillis() - b.lastErrorAt() < 30000) {
            detail = "Motivo: " + b.lastError();
        } else {
            detail = "El navegador no pudo conectar (" + description + ").";
        }
        detail = detail + "  Dirección: " + failedUrl;
        errorShownFor = failedUrl;
        web.loadDataWithBaseURL(failedUrl, Privacy.errorPageHtml("No se pudo abrir la página", detail), "text/html", "utf-8", failedUrl);
        omnibox.setText(failedUrl);
    }

    private WebResourceResponse textResponse(String mime, int status, String reason, byte[] body) {
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Cache-Control", "no-store");
        return new WebResourceResponse(mime, "utf-8", status, reason, headers, new ByteArrayInputStream(body));
    }

    private final class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            if (!req.isForMainFrame()) return false;
            String scheme = req.getUrl().getScheme();
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) return false;
            if ("about".equalsIgnoreCase(scheme)) return false;
            toast("Esta sesión sólo abre páginas web (http y https); se bloqueó un enlace de tipo «" + scheme + "».");
            return true;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            String host = u.getHost();
            if (INTERNAL_HOST.equalsIgnoreCase(host)) {
                if ("/inicio".equals(u.getPath())) {
                    return textResponse("text/html", 200, "OK", Privacy.startPageHtml(session.name).getBytes(StandardCharsets.UTF_8));
                }
                return textResponse("text/plain", 404, "Not Found", new byte[0]);
            }
            SessionDef s = session;
            if (s != null && s.blockTrackers && !req.isForMainFrame() && TrackerList.isTrackerUrl(u.toString())) {
                blockedInPage.incrementAndGet();
                return textResponse("text/plain", 403, "Blocked", new byte[0]);
            }
            return null;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            progress.setVisibility(View.VISIBLE);
            progress.setProgress(5);
            if (!omnibox.hasFocus() && !isInternal(url)) omnibox.setText(url);
            if (fallbackScript != null && !isInternal(url)) view.evaluateJavascript(fallbackScript, null);
            updateNav();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            progress.setVisibility(View.INVISIBLE);
            if (!omnibox.hasFocus() && !isInternal(url)) omnibox.setText(url);
            updateNav();
            SessionDef s = session;
            if (s != null && s.saveHistory && Validate.isWebUrl(url) && !url.equals(errorShownFor)) {
                final String u = url;
                final String t = view.getTitle();
                try {
                    worker.execute(new Runnable() {
                        @Override
                        public void run() {
                            history.add(u, t);
                        }
                    });
                } catch (RuntimeException ignored) {
                    // se está cerrando
                }
            }
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            updateNav();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
            if (!req.isForMainFrame()) return;
            final String failed = req.getUrl().toString();
            final int code = err.getErrorCode();
            final String desc = String.valueOf(err.getDescription());
            main.post(new Runnable() {
                @Override
                public void run() {
                    showLoadError(failed, code, desc);
                }
            });
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel(); // nunca se continúa con un certificado dudoso
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            if (view != web) return true;
            webHost.removeAllViews();
            view.destroy();
            web = null;
            createWebView();
            showStartPage();
            showNotice("La página dejó de responder (probablemente por falta de memoria) y se cerró.");
            return true;
        }
    }

    private final class Chrome extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            progress.setProgress(Math.max(5, newProgress));
            progress.setVisibility(newProgress >= 100 ? View.INVISIBLE : View.VISIBLE);
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            request.deny(); // cámara, micrófono, etc.: nunca
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
            callback.invoke(origin, false, false);
        }

        @Override
        public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
            cb.onReceiveValue(null);
            toast("Subir archivos no está disponible en esta versión.");
            return true;
        }

        @Override
        public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
            if (destroyed || isFinishing()) {
                result.cancel();
                return true;
            }
            new AlertDialog.Builder(SessionActivity.this)
                    .setTitle(hostOf(url))
                    .setMessage(message)
                    .setPositiveButton("Aceptar", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            result.confirm();
                        }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface d) {
                            result.cancel();
                        }
                    })
                    .show();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
            if (destroyed || isFinishing()) {
                result.cancel();
                return true;
            }
            new AlertDialog.Builder(SessionActivity.this)
                    .setTitle(hostOf(url))
                    .setMessage(message)
                    .setPositiveButton("Aceptar", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            result.confirm();
                        }
                    })
                    .setNegativeButton("Cancelar", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            result.cancel();
                        }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface d) {
                            result.cancel();
                        }
                    })
                    .show();
            return true;
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, android.webkit.JsPromptResult result) {
            result.cancel();
            return true;
        }
    }

    private static String hostOf(String url) {
        try {
            String h = Uri.parse(url).getHost();
            return h == null || h.isEmpty() ? "Página web" : h;
        } catch (RuntimeException e) {
            return "Página web";
        }
    }

    // ------------------------------------------------------------------ menú

    private void showMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        Menu menu = m.getMenu();
        menu.add(0, 1, 0, "Probar la conexión del proxy");
        menu.add(0, 2, 1, "Ver mi IP en esta sesión");
        menu.add(0, 3, 2, "Resumen de protecciones");
        menu.add(0, 4, 3, "Historial");
        menu.add(0, 5, 4, "Borrar historial");
        menu.add(0, 6, 5, "Copiar dirección");
        menu.add(0, 7, 6, "Abrir el gestor");
        menu.add(0, 8, 7, "Cerrar esta sesión");
        m.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(android.view.MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        testConnection();
                        break;
                    case 2:
                        openUrl("https://ipinfo.io/json");
                        break;
                    case 3:
                        showSummary();
                        break;
                    case 4:
                        showHistory();
                        break;
                    case 5:
                        clearHistory();
                        break;
                    case 6:
                        copyAddress();
                        break;
                    case 7:
                        openManager();
                        break;
                    case 8:
                        closeSession();
                        break;
                    default:
                        break;
                }
                return true;
            }
        });
        m.show();
    }

    private void openManager() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    private void copyAddress() {
        String u = web == null ? null : web.getUrl();
        if (errorShownFor != null) u = errorShownFor;
        if (isInternal(u)) {
            toast("No hay una dirección que copiar.");
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("dirección", u));
            toast("Dirección copiada.");
        }
    }

    private void showSummary() {
        SessionDef s = session;
        Bridge b = bridge;
        Route r = route;
        StringBuilder sb = new StringBuilder();
        sb.append("Ruta: ").append(r == null ? "…" : r.label()).append("\n\n");
        sb.append("• Rastreadores: ").append(s.blockTrackers ? "bloqueados" : "permitidos").append('\n');
        sb.append("• Cookies de terceros: ").append(s.blockThirdPartyCookies ? "bloqueadas" : "permitidas").append('\n');
        sb.append("• Reducción de huella digital: ").append(s.fingerprintProtection ? "sí" : "no").append('\n');
        sb.append("• WebRTC (puede revelar tu IP real): ").append(s.webrtcProtection ? "bloqueado" : "permitido").append('\n');
        sb.append("• Señales «no me rastrees» (GPC/DNT): ").append(s.sendPrivacySignals ? "sí" : "no").append('\n');
        sb.append("• Historial: ").append(s.saveHistory ? "se guarda en esta sesión" : "no se guarda").append('\n');
        sb.append("• Capturas de pantalla: ").append(s.secureScreen ? "bloqueadas" : "permitidas").append("\n\n");
        long trackers = blockedInPage.get() + (b == null ? 0 : b.trackersBlocked());
        sb.append("Rastreadores bloqueados en esta apertura: ").append(trackers).append('\n');
        if (b != null) {
            sb.append("Conexiones atendidas: ").append(b.connectionsAccepted()).append('\n');
            sb.append("Fallos de conexión: ").append(b.failures()).append('\n');
            if (b.lastErrorAt() > 0) sb.append("Último fallo: ").append(b.lastError()).append('\n');
        }
        sb.append("\nEsto reduce el rastreo, pero NO te hace anónimo ni te protege de todo: los sitios aún pueden reconocerte ")
                .append("por tu cuenta, tu comportamiento y otras señales.");
        Ui.info(this, "Protecciones de esta sesión", sb.toString());
    }

    private void showHistory() {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                final List<History.Entry> items = history.recent(100);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (destroyed) return;
                        if (items.isEmpty()) {
                            Ui.info(SessionActivity.this, "Historial", session.saveHistory
                                    ? "Todavía no hay páginas en el historial de esta sesión."
                                    : "Esta sesión no guarda historial (se puede activar en sus ajustes).");
                            return;
                        }
                        final CharSequence[] labels = new CharSequence[items.size()];
                        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
                        for (int i = 0; i < items.size(); i++) {
                            History.Entry e = items.get(i);
                            String title = e.title == null || e.title.isEmpty() ? e.url : e.title;
                            labels[i] = title + "\n" + e.url + "\n" + df.format(new Date(e.time));
                        }
                        new AlertDialog.Builder(SessionActivity.this)
                                .setTitle("Historial de esta sesión")
                                .setItems(labels, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int which) {
                                        openUrl(items.get(which).url);
                                    }
                                })
                                .setNegativeButton("Cerrar", null)
                                .show();
                    }
                });
            }
        });
    }

    private void clearHistory() {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                history.clear();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        toast("Historial borrado.");
                    }
                });
            }
        });
    }

    private void testConnection() {
        final AlertDialog[] dlg = new AlertDialog[1];
        final boolean[] cancelled = new boolean[1];
        dlg[0] = new AlertDialog.Builder(this)
                .setTitle("Probando la conexión…")
                .setMessage("Se compara tu IP real con la que ve el mundo a través del proxy. Puede tardar hasta unos 30 segundos.")
                .setNegativeButton("Cancelar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        cancelled[0] = true;
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface d) {
                        cancelled[0] = true;
                    }
                })
                .create();
        dlg[0].show();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                String outcome;
                try {
                    Store.Data d = store.read();
                    SessionDef cur = d.session(sid);
                    ProxyDef p = cur == null || cur.proxyId == null ? null : d.proxy(cur.proxyId);
                    if (p == null) {
                        outcome = "Esta sesión no tiene un proxy elegido. Ábrela en el gestor, edítala y elige uno.";
                    } else {
                        Upstream up = AppFiles.upstreamFor(p);
                        ProxyTester.Result res = ProxyTester.run(up, true, new ProxyTester.Options());
                        outcome = "Proxy: " + p.name + " (" + p.describe() + ")\n\n" + ProxyTester.describe(res);
                    }
                } catch (RuntimeException e) {
                    outcome = "No se pudo hacer la prueba: " + e.getMessage();
                }
                final String text = outcome;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (cancelled[0] || destroyed) return;
                        dlg[0].dismiss();
                        Ui.info(SessionActivity.this, "Resultado de la prueba", text);
                    }
                });
            }
        }, "gp-test");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ botón «atrás»

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - backAt < 2000) {
            closeSession();
            return;
        }
        backAt = now;
        toast("Pulsa «atrás» otra vez para cerrar la sesión.");
    }
}
