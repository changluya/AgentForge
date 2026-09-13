package com.changlu.agentforge.llm.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dependency-free HTTP transport based on JDK {@link HttpURLConnection}.
 *
 * <p>Streaming requests are consumed on daemon worker threads so the caller is not blocked
 * while an SSE response is being read.</p>
 *
 * @author changlu
 * @date 2026/09/13
 */
public final class JdkHttpTransport implements HttpTransport {

    private static final AtomicInteger STREAM_THREAD_SEQUENCE = new AtomicInteger();
    private static final ExecutorService STREAM_EXECUTOR = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable,
                    "agentforge-http-stream-" + STREAM_THREAD_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });

    @Override
    public HttpResponse execute(HttpRequest request) throws IOException {
        HttpURLConnection connection = openConnection(request);
        try {
            writeBody(connection, request.body());
            int status = connection.getResponseCode();
            InputStream stream = responseStream(connection, status);
            return new HttpResponse(status, readBody(stream));
        } finally {
            connection.disconnect();
        }
    }

    @Override
    public void executeStreaming(final HttpRequest request, final StreamingHttpResponseHandler handler) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        STREAM_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                stream(request, handler);
            }
        });
    }

    private static void stream(HttpRequest request, StreamingHttpResponseHandler handler) {
        HttpURLConnection connection = null;
        try {
            connection = openConnection(request);
            writeBody(connection, request.body());
            int status = connection.getResponseCode();
            Map<String, List<String>> headers = connection.getHeaderFields();
            handler.onOpen(status, headers);

            InputStream stream = responseStream(connection, status);
            if (stream != null) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        handler.onLine(line);
                    }
                } finally {
                    reader.close();
                }
            }
            handler.onComplete();
        } catch (Throwable error) {
            try {
                handler.onError(error);
            } catch (Throwable ignored) {
                // User callback failures must not escape the transport worker thread.
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static HttpURLConnection openConnection(HttpRequest request) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(request.url()).openConnection();
        connection.setRequestMethod(request.method());
        connection.setConnectTimeout(request.connectTimeoutMillis());
        connection.setReadTimeout(request.readTimeoutMillis());
        connection.setUseCaches(false);

        for (Map.Entry<String, String> header : request.headers().entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        return connection;
    }

    private static void writeBody(HttpURLConnection connection, String body) throws IOException {
        if (body == null || body.isEmpty()) {
            return;
        }
        connection.setDoOutput(true);
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(payload.length);
        OutputStream output = connection.getOutputStream();
        try {
            output.write(payload);
            output.flush();
        } finally {
            output.close();
        }
    }

    private static InputStream responseStream(HttpURLConnection connection, int status) throws IOException {
        return status >= 200 && status < 400
                ? connection.getInputStream()
                : connection.getErrorStream();
    }

    private static String readBody(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        try {
            StringBuilder builder = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                builder.append(buffer, 0, read);
            }
            return builder.toString();
        } finally {
            reader.close();
        }
    }
}
