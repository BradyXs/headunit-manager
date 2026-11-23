package com.bradyxu.headunitmanager;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

final class TlsCompat {
    private TlsCompat() { }

    static void enable(URLConnection connection) {
        if (android.os.Build.VERSION.SDK_INT >= 20 || !(connection instanceof HttpsURLConnection)) return;
        HttpsURLConnection https = (HttpsURLConnection) connection;
        https.setSSLSocketFactory(new Factory(https.getSSLSocketFactory()));
    }

    private static final class Factory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        Factory(SSLSocketFactory delegate) { this.delegate = delegate; }
        private Socket prepare(Socket socket) {
            if (socket instanceof SSLSocket) {
                SSLSocket ssl = (SSLSocket) socket;
                ArrayList<String> enabled = new ArrayList<>(Arrays.asList(ssl.getEnabledProtocols()));
                for (String supported : ssl.getSupportedProtocols())
                    if ((supported.equals("TLSv1.1") || supported.equals("TLSv1.2")) && !enabled.contains(supported)) enabled.add(supported);
                ssl.setEnabledProtocols(enabled.toArray(new String[0]));
            }
            return socket;
        }
        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException { return prepare(delegate.createSocket(s, host, port, autoClose)); }
        @Override public Socket createSocket(String host, int port) throws IOException { return prepare(delegate.createSocket(host, port)); }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException { return prepare(delegate.createSocket(host, port, local, localPort)); }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException { return prepare(delegate.createSocket(host, port)); }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException { return prepare(delegate.createSocket(host, port, local, localPort)); }
        @Override public Socket createSocket() throws IOException { return prepare(delegate.createSocket()); }
    }
}
