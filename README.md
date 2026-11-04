# Gestor Proxy (Android)

App de Android para navegar en **sesiones separadas**, cada una con su propio proxy.

- Cada sesión es una ventana aparte, en su **propio proceso de Android**, con su propio navegador (WebView) y su propia
  carpeta de datos: cookies, almacenamiento, caché, permisos e historial no se comparten con ninguna otra sesión.
- Proxies **HTTP, HTTPS y SOCKS5**, con usuario y contraseña opcionales (la contraseña se guarda cifrada con el
  Android Keystore).
- Interruptor de proxy por sesión. Apagado: sale directo o se bloquea todo, según lo elegido.
- Si el proxy falla, la sesión **no** se conecta directo.
- Los nombres de las páginas los resuelve el proxy (no el teléfono).
- Bloqueo de rastreadores, cookies de terceros opcionales, WebRTC bloqueado y reducción de huella digital.
- Prueba de conexión: si funciona, IP pública, país, latencia y errores explicados, comparada con la IP real.
- Crear, editar, duplicar, borrar datos y eliminar sesiones (hasta 10).

> **No te hace anónimo ni te protege de todo tipo de rastreo.** Reduce parte del rastreo y evita fugas obvias,
> pero los sitios pueden reconocerte por otras señales y tu proveedor de proxy ve a dónde te conectas.

## Instalar

1. Entra en [Releases](../../releases/latest) y descarga `GestorProxy.apk` desde el teléfono.
2. Ábrelo y permite «instalar apps de origen desconocido» para el navegador o el gestor de archivos que uses.
3. Necesita **Android 9 o superior** y un WebView actualizado (Google Play → «Android System WebView»).

Cada compilación se firma con una llave nueva (el repositorio es público y no guarda llaves). Por eso, **para instalar
una compilación más nueva hay que desinstalar antes la anterior** (se pierden sus sesiones y datos).

## Cómo se compila

GitHub Actions compila el APK con cada cambio (`.github/workflows/build.yml`): ejecuta las pruebas del núcleo
(`scripts/run-core-tests.sh`) y del script de WebRTC (`tests/js/webrtc.test.js`), compila con Gradle y publica
`GestorProxy.apk` en *Releases*.

## Cómo está hecho

| Parte | Qué hace |
| --- | --- |
| `core/` (Java puro, con pruebas) | Almacén de datos, validaciones, proxies HTTP/HTTPS/SOCKS5, **puente local**, prueba de conexión, lista de rastreadores, ajustes de privacidad |
| `app/` (Android) | Pantallas, sesiones (`S0`…`S9`, un proceso cada una), navegador, cifrado de contraseñas, registro de errores |

El navegador de cada sesión apunta **siempre** al puente local (`127.0.0.1`, puerto al azar). El puente decide si la
conexión sale por el proxy, directa o bloqueada, y corta las conexiones abiertas cuando cambia la ruta.

## Límites conocidos

- Versión de prueba: no se ha probado en todos los teléfonos.
- El motor web del sistema (Android System WebView) puede hacer conexiones propias (por ejemplo, actualizaciones)
  fuera del proxy.
- El puente local no tiene contraseña: otra app del mismo teléfono podría usarlo mientras una sesión está abierta.
- Sin descargas, subida de archivos ni extensiones.
- Máximo 10 sesiones.
- Si el WebView del teléfono no admite proxy por navegador, las sesiones no abren páginas (por seguridad).
