package com.gestorproxy.app;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText("Gestor Proxy - prueba de compilacion");
        tv.setTextSize(20);
        tv.setPadding(48, 96, 48, 48);
        setContentView(tv);
    }
}
