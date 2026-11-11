package com.locus.app.net;

import android.content.Context;

import com.locus.app.R;
import com.locus.app.util.AppExecutors;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;

/**
 * Minimal HTTP/HTTPS client on top of HttpURLConnection supporting GET, POST, PUT and DELETE,
 * plus helpers to run requests on a background thread and deliver the result on the UI thread.
 */
public final class HttpClient {
    private static final String USER_AGENT = "LOCUS/1.0 (Android student project)";

    private HttpClient() {
    }

    public static final class Response {
        public final int code;
        public final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    public interface StreamHandler<T> {
        T handle(InputStream in) throws Exception;
    }

    public interface Callback<T> {
        void onSuccess(T result);

        void onError(String message);
    }

    /** Blocking request that returns the whole body as a String. Call from a background thread. */
    public static Response request(String method, String url, String body, String contentType) throws IOException {
        return request(method, url, body, contentType, 15000, 60000);
    }

    /** Same, with explicit timeouts - searches use short ones so a slow server is skipped quickly. */
    public static Response request(String method, String url, String body, String contentType,
                                   int connectMs, int readMs) throws IOException {
        HttpURLConnection c = open(method, url, body, contentType, connectMs, readMs);
        try (InputStream in = c.getInputStream()) {
            return new Response(c.getResponseCode(), readAll(in));
        } finally {
            c.disconnect();
        }
    }

    public static Response get(String url) throws IOException {
        return request("GET", url, null, null);
    }

    /** Blocking request whose body is parsed while streaming (for large responses). */
    public static <T> T stream(String method, String url, String body, String contentType,
                               StreamHandler<T> handler) throws Exception {
        HttpURLConnection c = open(method, url, body, contentType, 15000, 60000);
        try (InputStream in = new BufferedInputStream(c.getInputStream())) {
            return handler.handle(in);
        } finally {
            c.disconnect();
        }
    }

    /** Runs the job on the IO pool and posts the result (or a readable error) on the main thread. */
    public static <T> void async(Context context, Callable<T> job, Callback<T> callback) {
        final Context app = context.getApplicationContext();
        AppExecutors.io().execute(() -> {
            try {
                final T result = job.call();
                AppExecutors.main().post(() -> callback.onSuccess(result));
            } catch (Exception e) {
                final String msg = friendlyMessage(app, e);
                AppExecutors.main().post(() -> callback.onError(msg));
            }
        });
    }

    public static String friendlyMessage(Context c, Exception e) {
        if (e instanceof UnknownHostException || e instanceof ConnectException) {
            return c.getString(R.string.net_no_connection);
        }
        if (e instanceof SocketTimeoutException) {
            return c.getString(R.string.net_timeout);
        }
        String m = e.getMessage();
        if (m != null && (m.contains("HTTP 429") || m.contains("HTTP 503") || m.contains("HTTP 504")
                || m.contains("HTTP 502"))) {
            return c.getString(R.string.net_busy);
        }
        return m == null ? e.getClass().getSimpleName() : m;
    }

    private static HttpURLConnection open(String method, String urlStr, String body, String contentType,
                                          int connectMs, int readMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        c.setRequestProperty("User-Agent", USER_AGENT);
        c.setRequestProperty("Accept", "application/json, application/xml;q=0.9, */*;q=0.8");
        boolean hasBody = body != null && (method.equals("POST") || method.equals("PUT"));
        if (hasBody) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", contentType == null ? "application/json; charset=utf-8" : contentType);
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(bytes);
            }
        }
        int code = c.getResponseCode();
        if (code >= 400) {
            c.disconnect();
            throw new IOException("Server returned HTTP " + code);
        }
        return c;
    }

    private static String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
        }
        return sb.toString();
    }
}
