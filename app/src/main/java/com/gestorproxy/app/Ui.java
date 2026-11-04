package com.gestorproxy.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Pequeñas herramientas para dibujar la interfaz con código (colores claros u oscuros según el teléfono). */
final class Ui {
    static final int PRIMARY = 0;
    static final int TONAL = 1;
    static final int FLAT = 2;
    static final int DANGER = 3;

    final Context ctx;
    final float density;
    final boolean night;
    final int bg;
    final int surface;
    final int surfaceAlt;
    final int text;
    final int textDim;
    final int line;
    final int primary;
    final int onPrimary;
    final int danger;
    final int success;
    final int warn;

    Ui(Context c) {
        ctx = c;
        density = c.getResources().getDisplayMetrics().density;
        night = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        if (night) {
            bg = 0xFF0F1419;
            surface = 0xFF1A2129;
            surfaceAlt = 0xFF232C36;
            text = 0xFFE8EDF2;
            textDim = 0xFF9AA7B4;
            line = 0xFF2E3945;
            primary = 0xFF5B9BFF;
            onPrimary = 0xFF08121F;
            danger = 0xFFFF7B7B;
            success = 0xFF4CD08A;
            warn = 0xFFFFC857;
        } else {
            bg = 0xFFF2F5F8;
            surface = 0xFFFFFFFF;
            surfaceAlt = 0xFFE8EEF4;
            text = 0xFF16212B;
            textDim = 0xFF5B6B7A;
            line = 0xFFDDE4EB;
            primary = 0xFF2563EB;
            onPrimary = 0xFFFFFFFF;
            danger = 0xFFC62828;
            success = 0xFF15803D;
            warn = 0xFFB45309;
        }
    }

    int dp(int v) {
        return Math.round(v * density);
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

    Drawable ripple(Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(night ? 0x33FFFFFF : 0x22000000), content, null);
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

    /** Tarjeta con fondo y borde suave. */
    LinearLayout card() {
        LinearLayout c = vertical();
        c.setBackground(outlined(surface, line, 14));
        int p = dp(14);
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
        TextView t = label(s, 12, text, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        return t;
    }

    /** Etiqueta pequeña con fondo de color (por ejemplo «Abierta»). */
    TextView chip(String s, int fill, int textColor) {
        TextView t = label(s, 11, textColor, true);
        t.setBackground(rounded(fill, 20));
        t.setPadding(dp(8), dp(2), dp(8), dp(2));
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

    // ------------------------------------------------------------------ botones y campos

    Button button(String label, int style, View.OnClickListener l) {
        Button b = new Button(ctx);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setStateListAnimator(null);
        b.setElevation(0f);
        b.setMinHeight(dp(42));
        b.setMinimumHeight(dp(42));
        b.setMinWidth(dp(64));
        b.setMinimumWidth(dp(64));
        b.setPadding(dp(14), dp(6), dp(14), dp(6));
        int fill;
        int fg;
        switch (style) {
            case PRIMARY:
                fill = primary;
                fg = onPrimary;
                break;
            case DANGER:
                fill = night ? 0x33FF7B7B : 0x1AC62828;
                fg = danger;
                break;
            case FLAT:
                fill = 0x00000000;
                fg = primary;
                break;
            default:
                fill = surfaceAlt;
                fg = text;
                break;
        }
        b.setTextColor(fg);
        b.setBackground(ripple(rounded(fill, 10)));
        if (l != null) b.setOnClickListener(l);
        return b;
    }

    EditText field(String hint, int inputType) {
        EditText e = new EditText(ctx);
        e.setHint(hint);
        e.setInputType(inputType);
        e.setSingleLine(true);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setTextColor(text);
        e.setHintTextColor(textDim);
        e.setBackground(outlined(surfaceAlt, line, 10));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
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
