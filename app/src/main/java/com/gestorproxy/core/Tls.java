package com.gestorproxy.core;

import java.io.IOException;
import java.net.Socket;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Envuelve un socket ya conectado en TLS verificando el certificado y el nombre del servidor. */
final class Tls {
    private Tls() {}

    /** Sólo para las pruebas automáticas (certificados de prueba). En la app real es siempre null. */
    static volatile SSLSocketFactory testFactory;

    static Socket wrap(Socket raw, String host, int port) throws IOException {
        SSLSocketFactory f = testFactory != null ? testFactory : (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket s = (SSLSocket) f.createSocket(raw, host, port, true);
        try {
            if (testFactory == null) {
                SSLParameters p = s.getSSLParameters();
                p.setEndpointIdentificationAlgorithm("HTTPS");
                s.setSSLParameters(p);
            }
            s.startHandshake();
            return s;
        } catch (IOException e) {
            try {
                s.close();
            } catch (IOException ignored) {
                // nada
            }
            throw e;
        } catch (RuntimeException e) {
            try {
                s.close();
            } catch (IOException ignored) {
                // nada
            }
            throw e;
        }
    }
}
