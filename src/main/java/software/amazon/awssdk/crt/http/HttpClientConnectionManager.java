/*
 * Copyright 2010-2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package software.amazon.awssdk.crt.http;

import java.net.URI;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import software.amazon.awssdk.crt.CRT;
import software.amazon.awssdk.crt.CrtResource;
import software.amazon.awssdk.crt.CrtRuntimeException;
import software.amazon.awssdk.crt.io.ClientBootstrap;
import software.amazon.awssdk.crt.io.SocketOptions;
import software.amazon.awssdk.crt.io.TlsContext;

/**
 * Manages a Pool of Http Connections
 */
public class HttpClientConnectionManager extends CrtResource {
    private static final String HTTP = "http";
    private static final String HTTPS = "https";
    private static final int DEFAULT_HTTP_PORT = 80;
    private static final int DEFAULT_HTTPS_PORT = 443;

    private final int windowSize;
    private final URI uri;
    private final int port;
    private final int maxConnections;

    public static HttpClientConnectionManager create(HttpClientConnectionManagerOptions options) {
        return new HttpClientConnectionManager(options);
    }

    private HttpClientConnectionManager(HttpClientConnectionManagerOptions options) {
        URI uri = options.getUri();
        if (uri == null) {  throw new IllegalArgumentException("URI must not be null"); }
        if (uri.getScheme() == null) { throw new IllegalArgumentException("URI does not have a Scheme"); }
        if (!HTTP.equals(uri.getScheme()) && !HTTPS.equals(uri.getScheme())) { throw new IllegalArgumentException("URI has unknown Scheme"); }
        if (uri.getHost() == null) { throw new IllegalArgumentException("URI does not have a Host name"); }

        ClientBootstrap clientBootstrap = options.getClientBootstrap();
        if (clientBootstrap == null) {  throw new IllegalArgumentException("ClientBootstrap must not be null"); }

        SocketOptions socketOptions = options.getSocketOptions();
        if (socketOptions == null) { throw new IllegalArgumentException("SocketOptions must not be null"); }

        boolean useTls = HTTPS.equals(uri.getScheme());
        TlsContext tlsContext = options.getTlsContext();
        if (useTls && tlsContext == null) { throw new IllegalArgumentException("TlsContext must not be null if https is used"); }

        int windowSize = options.getWindowSize();
        if (windowSize <= 0) { throw new  IllegalArgumentException("Window Size must be greater than zero."); }

        int bufferSize = options.getBufferSize();
        if (bufferSize <= 0) { throw new  IllegalArgumentException("Buffer Size must be greater than zero."); }

        int maxConnections = options.getMaxConnections();
        if (maxConnections <= 0) { throw new  IllegalArgumentException("Max Connections must be greater than zero."); }

        int port = uri.getPort();
        /* Pick a default port based on the scheme if one wasn't set in the URI */
        if (port == -1) {
            if (HTTP.equals(uri.getScheme()))  { port = DEFAULT_HTTP_PORT; }
            if (HTTPS.equals(uri.getScheme())) { port = DEFAULT_HTTPS_PORT; }
        }

        HttpProxyOptions proxyOptions = options.getProxyOptions();

        this.windowSize = windowSize;
        this.uri = uri;
        this.port = port;
        this.maxConnections = maxConnections;

        String proxyHost = null;
        int proxyPort = 0;
        TlsContext proxyTlsContext = null;
        int proxyAuthorizationType = 0;
        String proxyAuthorizationUsername = null;
        String proxyAuthorizationPassword = null;

        if (proxyOptions != null) {
            proxyHost = proxyOptions.getHost();
            proxyPort = proxyOptions.getPort();
            proxyTlsContext = proxyOptions.getTlsContext();
            proxyAuthorizationType = proxyOptions.getAuthorizationType().getValue();
            proxyAuthorizationUsername = proxyOptions.getAuthorizationUsername();
            proxyAuthorizationPassword = proxyOptions.getAuthorizationPassword();
        }

        acquireNativeHandle(httpClientConnectionManagerNew(this,
                                            clientBootstrap,
                                            socketOptions.getNativeHandle(),
                                            tlsContext,
                                            windowSize,
                                            uri.getHost(),
                                            port,
                                            maxConnections,
                                            proxyHost,
                                            proxyPort,
                                            proxyTlsContext,
                                            proxyAuthorizationType,
                                            proxyAuthorizationUsername,
                                            proxyAuthorizationPassword),
                                            (x)->httpClientConnectionManagerRelease(x));
    }

    /**
     * Request a HttpClientConnection from the Connection Pool.
     * @return A Future for a HttpClientConnection that will be completed when a connection is acquired.
     */
    public CompletableFuture<HttpClientConnection> acquireConnection() {
        CompletableFuture<HttpClientConnection> connRequest = new CompletableFuture<>();

        httpClientConnectionManagerAcquireConnection(this, connRequest, this.getNativeHandle());
        return connRequest;
    }

    /**
     * Releases this HttpClientConnection back into the Connection Pool, and allows another Request to acquire this connection.
     * @param conn
     */
    public void releaseConnection(HttpClientConnection conn) {
        conn.close();
    }

    protected void releaseConnectionPointer(long connection_ptr) {
        httpClientConnectionManagerReleaseConnection(this.getNativeHandle(), connection_ptr);
    }

    /*******************************************************************************
     * Getter methods
     ******************************************************************************/

    public int getMaxConnections() {
        return maxConnections;
    }

    public int getWindowSize() {
        return windowSize;
    }

    public URI getUri() {
        return uri;
    }

    /*******************************************************************************
     * Native methods
     ******************************************************************************/

    private static native long httpClientConnectionManagerNew(HttpClientConnectionManager thisObj,
                                                        ClientBootstrap client_bootstrap,
                                                        long socketOptions,
                                                        TlsContext tlsContext,
                                                        int windowSize,
                                                        String endpoint,
                                                        int port,
                                                        int maxConns,
                                                        String proxyHost,
                                                        int proxyPort,
                                                        TlsContext proxyTlsContext,
                                                        int proxyAuthorizationType,
                                                        String proxyAuthorizationUsername,
                                                        String proxyAuthorizationPassword
                                                        ) throws CrtRuntimeException;

    private static native void httpClientConnectionManagerRelease(long conn_manager) throws CrtRuntimeException;

    private static native void httpClientConnectionManagerAcquireConnection(HttpClientConnectionManager thisObj, CompletableFuture<HttpClientConnection> future, long conn_manager) throws CrtRuntimeException;

    private static native void httpClientConnectionManagerReleaseConnection(long conn_manager, long connection) throws CrtRuntimeException;

}
