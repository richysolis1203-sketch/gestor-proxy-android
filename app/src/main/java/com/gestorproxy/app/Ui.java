package com.gestorproxy.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.util.Locale;

/**
 * Pequeñas herramientas para dibujar la interfaz con código. El diseño es siempre oscuro, morado, con degradados
 * y brillos suaves.
 */
final class Ui {
    static final int PRIMARY = 0;
    static final int TONAL = 1;
    static final int FLAT = 2;
    static final int DANGER = 3;

    final Context ctx;
    final float density;
    /** El diseño es siempre oscuro (se conserva el nombre por compatibilidad). */
    final boolean night = true;
    final int bg = 0xFF0B0714;
    final int bgDeep = 0xFF120A26;
    final int surface = 0xFF160F29;
    final int surfaceAlt = 0xFF251B42;
    final int text = 0xFFF4F1FF;
    final int textDim = 0xFFA9A1C8;
    final int line = 0xFF3B2E63;
    final int primary = 0xFF8B5CF6;
    final int primaryDark = 0xFF6D28D9;
    final int primaryEnd = 0xFFA855F7;
    final int lilac = 0xFFC4B5FD;
    final int accent2 = 0xFF38BDF8;
    final int onPrimary = 0xFFFFFFFF;
    final int danger = 0xFFFB7185;
    final int success = 0xFF34D399;
    final int warn = 0xFFFBBF24;

    Ui(Context c) {
        ctx = c;
        density = c.getResources().getDisplayMetrics().density;
    }

    int dp(int v) {
        return Math.round(v * density);
    }

    // ------------------------------------------------------------------ colores

    /** El mismo color con otra transparencia (a de 0 a 255). */
    static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | ((a & 0xFF) << 24);
    }

    /** Mezcla dos colores: t = 0 es a, t = 1 es b. */
    static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) * (1f - t) + ((b >> 16) & 0xFF) * t);
        int g = Math.round(((a >> 8) & 0xFF) * (1f - t) + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1f - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** Oscurece (f menor que 1) o aclara (f mayor que 1) un color. */
    static int shade(int color, float f) {
        int r = Math.min(255, Math.round(((color >> 16) & 0xFF) * f));
        int g = Math.min(255, Math.round(((color >> 8) & 0xFF) * f));
        int b = Math.min(255, Math.round((color & 0xFF) * f));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Lo que se muestra dentro del cuadro de color de una sesión: el número del nombre o su primera letra. */
    static String initial(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) return "?";
        int e = n.length();
        int s = e;
        while (s > 0 && Character.isDigit(n.charAt(s - 1))) s--;
        if (s < e && e - s <= 2) return n.substring(s, e);
        return n.substring(0, 1).toUpperCase(Locale.getDefault());
    }

    // ------------------------------------------------------------------ formas

    GradientDrawable rounded(int fill, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    GradientDrawable outlined(int fill, int stroke, int radiusDp) {
        GradientDrawable d = rounded(fill, radiusDp);
        d.setStroke(Math.max(1, dp(1)), stroke);
        return d;
    }

    GradientDrawable gradient(int from, int to, int radiusDp, GradientDrawable.Orientation o) {
        GradientDrawable d = new GradientDrawable(o, new int[] {from, to});
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    Drawable ripple(Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, null);
    }

    /** Fondo de tarjeta: borde con degradado morado→azul y relleno translúcido. */
    Drawable glowCard(int fill, int radiusDp) {
        GradientDrawable border = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {alpha(primary, 0xB0), alpha(accent2, 0x58), alpha(primary, 0x3C)});
        border.setCornerRadius(dp(radiusDp));
        GradientDrawable inner = rounded(fill, Math.max(1, radiusDp - 1));
        LayerDrawable l = new LayerDrawable(new Drawable[] {border, inner});
        int in = Math.max(1, Math.round(density * 1.2f));
        l.setLayerInset(1, in, in, in, in);
        return l;
    }

    /** Fondo de la pantalla: degradado oscuro con dos resplandores suaves (arriba a la derecha y abajo a la izquierda). */
    Drawable rootBackground() {
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {bg, bgDeep});
        GradientDrawable g1 = new GradientDrawable();
        g1.setShape(GradientDrawable.RECTANGLE);
        g1.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g1.setGradientCenter(0.92f, 0.04f);
        g1.setGradientRadius(dp(320));
        g1.setColors(new int[] {0x668B5CF6, 0x00000000});
        GradientDrawable g2 = new GradientDrawable();
        g2.setShape(GradientDrawable.RECTANGLE);
        g2.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g2.setGradientCenter(0.05f, 0.97f);
        g2.setGradientRadius(dp(300));
        g2.setColors(new int[] {0x442563EB, 0x00000000});
        return new LayerDrawable(new Drawable[] {base, g1, g2});
    }

    /** Sombra de color alrededor de una vista (brillo). */
    void glow(View v, int color, int elevDp) {
        v.setElevation(dp(elevDp));
        v.setOutlineSpotShadowColor(color);
        v.setOutlineAmbientShadowColor(color);
    }

    // ------------------------------------------------------------------ contenedores

    LinearLayout vertical() {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    LinearLayout.LayoutParams lpWeight(float weight) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }

    LinearLayout.LayoutParams lpMargins(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    /** Tarjeta con borde de degradado y relleno translúcido. */
    LinearLayout card() {
        LinearLayout c = vertical();
        c.setBackground(glowCard(alpha(surface, 0xE8), 18));
        int p = dp(16);
        c.setPadding(p, p, p, p);
        return c;
    }

    View space(int heightDp) {
        View v = new View(ctx);
        v.setLayoutParams(lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp)));
        return v;
    }

    View divider() {
        View v = new View(ctx);
        v.setBackgroundColor(line);
        v.setLayoutParams(lp(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        return v;
    }

    ScrollView scroll(View child) {
        ScrollView s = new ScrollView(ctx);
        s.setFillViewport(false);
        s.addView(child, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return s;
    }

    // ------------------------------------------------------------------ texto

    TextView label(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    TextView body(String s) {
        TextView t = label(s, 14, text, false);
        t.setLineSpacing(0f, 1.15f);
        return t;
    }

    TextView dim(String s) {
        TextView t = label(s, 13, textDim, false);
        t.setLineSpacing(0f, 1.1f);
        return t;
    }

    TextView title(String s) {
        return label(s, 17, text, true);
    }

    TextView mono(String s) {
        TextView t = label(s, 12, lilac, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        return t;
    }

    /** Etiqueta pequeña con fondo de color (por ejemplo «Abierta»). */
    TextView chip(String s, int fill, int textColor) {
        TextView t = label(s, 11, textColor, true);
        t.setBackground(rounded(fill, 20));
        t.setPadding(dp(10), dp(3), dp(10), dp(3));
        return t;
    }

    View dot(int color, int sizeDp) {
        View v = new View(ctx);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        v.setBackground(d);
        v.setLayoutParams(lp(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    /** Punto de estado con un anillo del color de la tarjeta, para que destaque sobre el cuadro de color. */
    View ringDot(int color, int sizeDp) {
        View v = new View(ctx);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setStroke(Math.max(2, dp(2)), 0xFF160F29);
        v.setBackground(d);
        v.setLayoutParams(lp(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    // ------------------------------------------------------------------ imágenes e íconos

    /** Ícono vectorial pintado de un color; null si no se pudo cargar (nunca debe romper la pantalla). */
    Drawable icon(int res, int color) {
        try {
            Drawable d = ctx.getDrawable(res);
            if (d == null) return null;
            d = d.mutate();
            d.setTint(color);
            return d;
        } catch (RuntimeException e) {
            return null;
        }
    }

    ImageView iconView(int res, int color, int sizeDp) {
        ImageView v = new ImageView(ctx);
        Drawable d = icon(res, color);
        if (d != null) v.setImageDrawable(d);
        v.setLayoutParams(lp(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    /** Imagen decorativa; null si no se pudo cargar. */
    ImageView picture(int res, ImageView.ScaleType type) {
        try {
            ImageView v = new ImageView(ctx);
            v.setScaleType(type);
            v.setImageResource(res);
            v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            return v;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ piezas con estilo

    /** Botón con ícono y texto centrados; puede cambiar de estilo (las pestañas lo usan). */
    static final class Pill extends LinearLayout {
        private final Ui ui;
        private final int iconRes;
        final ImageView icon;
        final TextView label;

        Pill(Ui ui, String text, int style, int iconRes, int sp) {
            super(ui.ctx);
            this.ui = ui;
            this.iconRes = iconRes;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER);
            setClickable(true);
            setFocusable(true);
            setMinimumHeight(ui.dp(44));
            setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8));
            icon = new ImageView(ui.ctx);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(ui.dp(18), ui.dp(18));
            ip.setMargins(0, 0, ui.dp(6), 0);
            icon.setVisibility(iconRes != 0 ? View.VISIBLE : View.GONE);
            addView(icon, ip);
            label = new TextView(ui.ctx);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setMaxLines(1);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setText(text);
            addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            setStyle(style);
        }

        void setText(String t) {
            label.setText(t);
        }

        void setStyle(int style) {
            int fg;
            Drawable background;
            boolean glowing = false;
            switch (style) {
                case Ui.PRIMARY:
                    fg = ui.onPrimary;
                    background = ui.ripple(ui.gradient(ui.primaryDark, ui.primaryEnd, 14, GradientDrawable.Orientation.LEFT_RIGHT));
                    glowing = true;
                    break;
                case Ui.DANGER:
                    fg = ui.danger;
                    background = ui.ripple(ui.outlined(Ui.alpha(ui.danger, 0x22), Ui.alpha(ui.danger, 0x66), 14));
                    break;
                case Ui.FLAT:
                    fg = ui.lilac;
                    background = ui.ripple(ui.rounded(0x00000000, 14));
                    break;
                default:
                    fg = ui.text;
                    background = ui.ripple(ui.outlined(ui.surfaceAlt, ui.line, 14));
                    break;
            }
            setBackground(background);
            if (glowing) ui.glow(this, ui.primary, 6);
            else setElevation(0f);
            label.setTextColor(fg);
            Drawable d = iconRes == 0 ? null : ui.icon(iconRes, fg);
            if (d != null) icon.setImageDrawable(d);
        }
    }

    Pill pill(String label, int style, int iconRes, int sp, View.OnClickListener l) {
        Pill p = new Pill(this, label, style, iconRes, sp);
        if (l != null) p.setOnClickListener(l);
        return p;
    }

    /** Aviso con ícono, del color dado (por ejemplo «Proxy APAGADO…»). */
    LinearLayout notice(int iconRes, String message, int color) {
        LinearLayout r = horizontal();
        r.setBackground(outlined(alpha(color, 0x1F), alpha(color, 0x66), 12));
        r.setPadding(dp(10), dp(8), dp(12), dp(8));
        r.addView(iconView(iconRes, color, 18), lp(dp(18), dp(18)));
        TextView t = label(message, 13, color, true);
        t.setLineSpacing(0f, 1.1f);
        t.setPadding(dp(8), 0, 0, 0);
        r.addView(t, lpWeight(1f));
        return r;
    }

    /** Cuadro con el color de una sesión y su número o inicial. */
    TextView avatar(String initial, int color, int sizeDp) {
        TextView t = label(initial, sizeDp >= 46 ? 19 : 15, 0xFFFFFFFF, true);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(1);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {shade(color, 1.2f), shade(color, 0.6f)});
        g.setCornerRadius(dp(14));
        t.setBackground(g);
        t.setLayoutParams(lp(dp(sizeDp), dp(sizeDp)));
        return t;
    }

    /** Cuadro redondeado con un ícono dentro. */
    FrameLayout iconTile(int iconRes, int color, int sizeDp) {
        FrameLayout f = new FrameLayout(ctx);
        f.setBackground(outlined(alpha(color, 0x2E), alpha(color, 0x80), 14));
        int in = dp(sizeDp / 2);
        f.addView(iconView(iconRes, color, sizeDp / 2), new FrameLayout.LayoutParams(in, in, Gravity.CENTER));
        f.setLayoutParams(lp(dp(sizeDp), dp(sizeDp)));
        return f;
    }

    /** Ficha con un número grande y su nombre (por ejemplo «3 Sesiones»). */
    LinearLayout statTile(int iconRes, int iconColor, String value, String caption) {
        LinearLayout t = vertical();
        t.setBackground(glowCard(alpha(surface, 0xE6), 16));
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
        t.addView(iconView(iconRes, iconColor, 18), lp(dp(18), dp(18)));
        TextView v = label(value, 24, text, true);
        v.setPadding(0, dp(4), 0, 0);
        t.addView(v);
        t.addView(label(caption, 12, textDim, false));
        return t;
    }

    void styleSwitch(Switch sw) {
        int[][] states = new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}};
        sw.setThumbTintList(new ColorStateList(states, new int[] {0xFFEDE9FE, 0xFFB9B0D6}));
        sw.setTrackTintList(new ColorStateList(states, new int[] {alpha(primary, 0xCC), 0xFF3B2E63}));
    }

    // ------------------------------------------------------------------ botones y campos

    /** Botón de texto (para formularios y cuadros de diálogo). */
    Button button(String label, int style, View.OnClickListener l) {
        Button b = new Button(ctx);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setStateListAnimator(null);
        b.setElevation(0f);
        b.setMinHeight(dp(44));
        b.setMinimumHeight(dp(44));
        b.setMinWidth(dp(64));
        b.setMinimumWidth(dp(64));
        b.setPadding(dp(14), dp(6), dp(14), dp(6));
        int fg;
        Drawable background;
        switch (style) {
            case PRIMARY:
                fg = onPrimary;
                background = ripple(gradient(primaryDark, primaryEnd, 14, GradientDrawable.Orientation.LEFT_RIGHT));
                glow(b, primary, 5);
                break;
            case DANGER:
                fg = danger;
                background = ripple(outlined(alpha(danger, 0x22), alpha(danger, 0x66), 14));
                break;
            case FLAT:
                fg = lilac;
                background = ripple(rounded(0x00000000, 14));
                break;
            default:
                fg = text;
                background = ripple(outlined(surfaceAlt, line, 14));
                break;
        }
        b.setTextColor(fg);
        b.setBackground(background);
        if (l != null) b.setOnClickListener(l);
        return b;
    }

    EditText field(String hint, int inputType) {
        EditText e = new EditText(ctx);
        e.setHint(hint);
        e.setInputType(inputType); // sin setSingleLine(): reemplazaría el ocultado de la contraseña
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setTextColor(text);
        e.setHintTextColor(textDim);
        e.setBackground(outlined(surfaceAlt, line, 12));
        e.setPadding(dp(14), dp(11), dp(14), dp(11));
        return e;
    }

    EditText textField(String hint) {
        return field(hint, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    }

    // ------------------------------------------------------------------ cuadros de diálogo

    static void info(Activity a, String title, CharSequence message) {
        if (a.isFinishing() || a.isDestroyed()) return;
        new AlertDialog.Builder(a).setTitle(title).setMessage(message).setPositiveButton("Aceptar", null).show();
    }

    static void confirm(Activity a, String title, CharSequence message, String yes, final Runnable onYes) {
        if (a.isFinishing() || a.isDestroyed()) return;
        new AlertDialog.Builder(a)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton(yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        onYes.run();
                    }
                })
                .show();
    }
}
