package com.gestorproxy.app;

import android.app.Application;

/** Se crea una vez en cada proceso de la app (el gestor y cada sesión). Sólo instala el registro de errores. */
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(this);
    }
}
