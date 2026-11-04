package com.gestorproxy.app;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import com.gestorproxy.core.ProxyDef;
import com.gestorproxy.core.SessionDef;
import com.gestorproxy.core.Store;
import com.gestorproxy.core.Upstream;
import com.gestorproxy.core.Validate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Formularios (cuadros de diálogo) para crear y editar proxies y sesiones. */
final class Forms {
    private Forms() {}

    private static final String[] PROTOCOL_LABELS = {"SOCKS5", "HTTP", "HTTPS"};
    private static final String[] PROTOCOL_VALUES = {"socks5", "http", "https"};

    private static TextView fieldLabel(Ui ui, String s) {
        TextView t = ui.label(s, 12, ui.textDim, true);
        t.setPadding(ui.dp(2), ui.dp(12), 0, ui.dp(4));
        return t;
    }

    private static Spinner spinner(Context c, List<String> items) {
        Spinner sp = new Spinner(c);
        ArrayAdapter<String> ad = new ArrayAdapter<String>(c, android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        return sp;
    }

    private static CheckBox check(Ui ui, String label, boolean checked) {
        CheckBox c = new CheckBox(ui.ctx);
        c.setText(label);
        c.setTextColor(ui.text);
        c.setTextSize(14);
        c.setChecked(checked);
        c.setPadding(ui.dp(4), ui.dp(6), 0, ui.dp(6));
        return c;
    }

    // ------------------------------------------------------------------ proxy

    static void proxyForm(final MainActivity a, final Ui ui, final ProxyDef existing) {
        final boolean editing = existing != null;
        LinearLayout box = ui.vertical();
        box.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8));

        final EditText paste = ui.textField("usuario:clave@servidor:puerto");
        if (!editing) {
            box.addView(fieldLabel(ui, "PEGAR PROXY COMPLETO (OPCIONAL)"));
            LinearLayout row = ui.horizontal();
            row.addView(paste, ui.lpWeight(1f));
            Button fill = ui.button("Rellenar", Ui.TONAL, null);
            row.addView(fill, ui.lpMargins(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 8, 0, 0, 0));
            box.addView(row);
            box.addView(ui.dim("Acepta: servidor:puerto, usuario:clave@servidor:puerto, socks5://usuario:clave@servidor:puerto, "
                    + "servidor:puerto:usuario:clave."));
            fill.setTag("fill");
        }

        box.addView(fieldLabel(ui, "NOMBRE"));
        final EditText name = ui.textField("Por ejemplo: Proxy de casa");
        box.addView(name);

        box.addView(fieldLabel(ui, "TIPO"));
        List<String> labels = new ArrayList<String>();
        for (String l : PROTOCOL_LABELS) labels.add(l);
        final Spinner type = spinner(ui.ctx, labels);
        box.addView(type);

        box.addView(fieldLabel(ui, "SERVIDOR (NOMBRE O IP)"));
        final EditText host = ui.field("ejemplo.com o 203.0.113.5",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.addView(host);

        box.addView(fieldLabel(ui, "PUERTO"));
        final EditText port = ui.field("1080", InputType.TYPE_CLASS_NUMBER);
        box.addView(port);

        box.addView(fieldLabel(ui, "USUARIO (SI LO NECESITA)"));
        final EditText user = ui.field("", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        user.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        box.addView(user);

        box.addView(fieldLabel(ui, "CONTRASEÑA (SI LA NECESITA)"));
        final EditText pass = ui.field(editing && existing.hasPassword() ? "(se conserva la actual si no escribes nada)" : "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pass.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        box.addView(pass);
        final CheckBox removePass = check(ui, "Quitar la contraseña guardada", false);
        if (editing && existing.hasPassword()) box.addView(removePass);

        final TextView error = ui.label("", 13, ui.danger, true);
        error.setPadding(0, ui.dp(12), 0, 0);
        error.setVisibility(View.GONE);
        box.addView(error);

        if (editing) {
            name.setText(existing.name);
            for (int i = 0; i < PROTOCOL_VALUES.length; i++) {
                if (PROTOCOL_VALUES[i].equals(existing.protocol)) type.setSelection(i);
            }
            host.setText(existing.host);
            port.setText(String.valueOf(existing.port));
            user.setText(existing.username);
        } else {
            port.setText("1080");
        }

        // «Rellenar» a partir de un proxy pegado
        if (!editing) {
            View fillBtn = findByTag(box, "fill");
            if (fillBtn != null) {
                fillBtn.setOnClickListener(v -> {
                    Validate.ParsedProxy pp = Validate.parseProxyText(paste.getText().toString());
                    if (!pp.ok()) {
                        error.setText(pp.error);
                        error.setVisibility(View.VISIBLE);
                        return;
                    }
                    error.setVisibility(View.GONE);
                    host.setText(pp.host);
                    port.setText(pp.port);
                    user.setText(pp.username);
                    pass.setText(pp.password);
                    if (pp.protocol != null) {
                        for (int i = 0; i < PROTOCOL_VALUES.length; i++) {
                            if (PROTOCOL_VALUES[i].equals(pp.protocol)) type.setSelection(i);
                        }
                    }
                    if (name.getText().toString().trim().isEmpty()) name.setText(pp.host);
                });
            }
        }

        final AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle(editing ? "Editar proxy" : "Añadir proxy")
                .setView(ui.scroll(box))
                .setPositiveButton("Guardar", null)
                .setNeutralButton("Probar", null)
                .setNegativeButton("Cancelar", null)
                .create();
        dlg.show();

        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Validate.ProxyChecked c = validate(name, type, host, port, user, pass);
            if (!c.ok()) {
                showErrors(error, c.errors);
                return;
            }
            ProxyDef p = editing ? existing.copy() : new ProxyDef();
            p.name = c.name;
            p.protocol = c.protocol;
            p.host = c.host;
            p.port = c.port;
            p.username = c.username;
            if (c.username.isEmpty()) {
                p.passwordEnc = "";
            } else if (removePass.isChecked()) {
                p.passwordEnc = "";
            } else if (!c.password.isEmpty()) {
                p.passwordEnc = SecretBox.encrypt(c.password);
            } else if (!editing) {
                p.passwordEnc = "";
            } // si se edita y no se escribió nada: se conserva la contraseña guardada
            dlg.dismiss();
            a.saveProxy(p, !editing);
        });

        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            String nm = name.getText().toString().trim();
            Validate.ProxyChecked c = validate(nm.isEmpty() ? "prueba" : name.getText().toString(), type, host, port, user, pass);
            if (!c.ok()) {
                showErrors(error, c.errors);
                return;
            }
            error.setVisibility(View.GONE);
            String password = c.password;
            if (password.isEmpty() && editing && existing.hasPassword() && !removePass.isChecked() && !c.username.isEmpty()) {
                String d = SecretBox.decrypt(existing.passwordEnc);
                if (d == null) {
                    error.setText("No se pudo leer la contraseña guardada. Escríbela de nuevo.");
                    error.setVisibility(View.VISIBLE);
                    return;
                }
                password = d;
            }
            a.runTest(new Upstream(c.protocol, c.host, c.port, c.username, c.username.isEmpty() ? "" : password),
                    c.protocol + "://" + c.host + ":" + c.port);
        });
    }

    private static View findByTag(View root, Object tag) {
        if (tag.equals(root.getTag())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View f = findByTag(g.getChildAt(i), tag);
                if (f != null) return f;
            }
        }
        return null;
    }

    private static Validate.ProxyChecked validate(
            EditText name, Spinner type, EditText host, EditText port, EditText user, EditText pass) {
        return validate(name.getText().toString(), type, host, port, user, pass);
    }

    private static Validate.ProxyChecked validate(
            String name, Spinner type, EditText host, EditText port, EditText user, EditText pass) {
        int sel = Math.max(0, Math.min(PROTOCOL_VALUES.length - 1, type.getSelectedItemPosition()));
        return Validate.validateProxyInput(name, PROTOCOL_VALUES[sel], host.getText().toString(), port.getText().toString(),
                user.getText().toString(), pass.getText().toString());
    }

    private static void showErrors(TextView error, Map<String, String> errors) {
        StringBuilder sb = new StringBuilder();
        for (String m : errors.values()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("• ").append(m);
        }
        error.setText(sb.toString());
        error.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ sesión

    static void sessionForm(final MainActivity a, final Ui ui, final SessionDef existing, final Store.Data data) {
        final boolean editing = existing != null;
        final SessionDef base = editing ? existing : SessionDef.create("", Math.max(0, data.freeSlot()), System.currentTimeMillis());
        LinearLayout box = ui.vertical();
        box.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8));

        box.addView(fieldLabel(ui, "NOMBRE"));
        final EditText name = ui.textField("Por ejemplo: Tienda 1");
        name.setText(editing ? existing.name : "Sesión " + (data.sessions.size() + 1));
        box.addView(name);

        // Proxy
        box.addView(fieldLabel(ui, "PROXY DE ESTA SESIÓN"));
        final List<String> proxyLabels = new ArrayList<String>();
        final List<String> proxyIds = new ArrayList<String>();
        proxyLabels.add("Sin proxy");
        proxyIds.add(null);
        int selected = 0;
        for (ProxyDef p : data.proxies) {
            proxyLabels.add(p.name + "  (" + p.describe() + ")");
            proxyIds.add(p.id);
            if (base.proxyId != null && base.proxyId.equals(p.id)) selected = proxyLabels.size() - 1;
        }
        final Spinner proxySpinner = spinner(ui.ctx, proxyLabels);
        proxySpinner.setSelection(selected);
        box.addView(proxySpinner);
        if (data.proxies.isEmpty()) {
            box.addView(ui.dim("Todavía no hay proxies. Añádelos en la pestaña «Proxies» y luego edita esta sesión para elegirlo."));
        }

        final Switch useProxy = new Switch(ui.ctx);
        useProxy.setText("Usar el proxy (interruptor)");
        useProxy.setTextColor(ui.text);
        useProxy.setChecked(editing ? existing.proxyEnabled : selected != 0);
        useProxy.setPadding(0, ui.dp(12), 0, ui.dp(4));
        box.addView(useProxy);

        box.addView(fieldLabel(ui, "SI EL PROXY ESTÁ APAGADO"));
        List<String> offLabels = new ArrayList<String>();
        offLabels.add("Salir directo (con mi IP real)");
        offLabels.add("Bloquear todo (sin conexión)");
        final Spinner off = spinner(ui.ctx, offLabels);
        off.setSelection(SessionDef.OFF_BLOCK.equals(base.onProxyOff) ? 1 : 0);
        box.addView(off);

        box.addView(fieldLabel(ui, "PÁGINA DE INICIO (OPCIONAL)"));
        final EditText start = ui.field("https://ejemplo.com",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        start.setText(base.startUrl);
        box.addView(start);

        box.addView(fieldLabel(ui, "PRIVACIDAD"));
        final CheckBox trackers = check(ui, "Bloquear rastreadores conocidos", base.blockTrackers);
        final CheckBox cookies3 = check(ui, "Bloquear cookies de terceros", base.blockThirdPartyCookies);
        final CheckBox fp = check(ui, "Reducir la huella digital del navegador", base.fingerprintProtection);
        final CheckBox rtc = check(ui, "Bloquear WebRTC (puede revelar tu IP real)", base.webrtcProtection);
        final CheckBox gpc = check(ui, "Enviar señales «no me rastrees» (GPC / DNT)", base.sendPrivacySignals);
        final CheckBox hist = check(ui, "Guardar historial en esta sesión", base.saveHistory);
        final CheckBox secure = check(ui, "Impedir capturas de pantalla", base.secureScreen);
        box.addView(trackers);
        box.addView(cookies3);
        box.addView(fp);
        box.addView(rtc);
        box.addView(gpc);
        box.addView(hist);
        box.addView(secure);

        box.addView(fieldLabel(ui, "COLOR"));
        final int[] chosen = {Math.floorMod(base.color, SessionDef.COLORS.length)};
        final LinearLayout colorRow = ui.horizontal();
        final List<View> dots = new ArrayList<View>();
        for (int i = 0; i < SessionDef.COLORS.length; i++) {
            final int idx = i;
            View d = new View(ui.ctx);
            LinearLayout.LayoutParams lp = ui.lpMargins(ui.dp(30), ui.dp(30), 0, 0, 8, 0);
            d.setLayoutParams(lp);
            d.setOnClickListener(v -> {
                chosen[0] = idx;
                paintDots(ui, dots, chosen[0]);
            });
            dots.add(d);
            colorRow.addView(d);
        }
        paintDots(ui, dots, chosen[0]);
        box.addView(colorRow);

        final TextView error = ui.label("", 13, ui.danger, true);
        error.setPadding(0, ui.dp(12), 0, 0);
        error.setVisibility(View.GONE);
        box.addView(error);

        final AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle(editing ? "Editar sesión" : "Nueva sesión")
                .setView(ui.scroll(box))
                .setPositiveButton(editing ? "Guardar" : "Crear", null)
                .setNegativeButton("Cancelar", null)
                .create();
        dlg.show();

        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Validate.Check n = Validate.validateName(name.getText().toString(), "la sesión");
            if (!n.ok()) {
                error.setText(n.error);
                error.setVisibility(View.VISIBLE);
                return;
            }
            String startUrl = "";
            String typed = start.getText().toString().trim();
            if (!typed.isEmpty()) {
                Validate.UrlResult u = Validate.normalizeUserUrl(typed);
                if (!u.ok || !"url".equals(u.kind)) {
                    error.setText("La página de inicio debe ser una dirección web, por ejemplo https://ejemplo.com");
                    error.setVisibility(View.VISIBLE);
                    return;
                }
                startUrl = u.url;
            }
            String proxyId = proxyIds.get(Math.max(0, proxySpinner.getSelectedItemPosition()));
            if (useProxy.isChecked() && proxyId == null) {
                error.setText("Para usar el proxy primero elige uno en «Proxy de esta sesión».");
                error.setVisibility(View.VISIBLE);
                return;
            }
            SessionDef s = base.copy();
            s.name = n.value;
            s.proxyId = proxyId;
            s.proxyEnabled = useProxy.isChecked() && proxyId != null;
            s.onProxyOff = off.getSelectedItemPosition() == 1 ? SessionDef.OFF_BLOCK : SessionDef.OFF_DIRECT;
            s.startUrl = startUrl;
            s.blockTrackers = trackers.isChecked();
            s.blockThirdPartyCookies = cookies3.isChecked();
            s.fingerprintProtection = fp.isChecked();
            s.webrtcProtection = rtc.isChecked();
            s.sendPrivacySignals = gpc.isChecked();
            s.saveHistory = hist.isChecked();
            s.secureScreen = secure.isChecked();
            s.color = chosen[0];
            dlg.dismiss();
            a.saveSession(s, !editing);
        });
    }

    private static void paintDots(Ui ui, List<View> dots, int selected) {
        for (int i = 0; i < dots.size(); i++) {
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            g.setColor(SessionDef.COLORS[i]);
            if (i == selected) g.setStroke(ui.dp(3), ui.text);
            dots.get(i).setBackground(g);
        }
    }
}
