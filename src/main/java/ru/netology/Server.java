package ru.netology;

import org.apache.hc.core5.net.URIBuilder;
import org.apache.hc.core5.net.WWWFormCodec;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Server {
    private final List<String> validPaths = List.of(
            "/index.html",
            "/spring.svg",
            "/spring.png",
            "/resources.html",
            "/styles.css",
            "/app.js",
            "/links.html",
            "/forms.html",
            "/classic.html",
            "/events.html",
            "/events.js",
            "/form.html",
            "/multipart.html"
    );
    public static final String GET = "GET";
    public static final String POST = "POST";
    private final List<String> validMethods = List.of(GET, POST);

    private final byte[] requestLineDelimiterBytes = new byte[]{'\r', '\n'};
    private final byte[] headersDelimiterBytes = new byte[]{'\r', '\n', '\r', '\n'};

    private final String CONTENT_LENGTH_HEADER = "Content-Length";
    private final String CONTENT_TYPE_HEADER = "Content-Type";
    private final String X_WWW_FORM_URLENCODED = "application/x-www-form-urlencoded";

    private final int port;
    private final ExecutorService threadPool;
    private final int requestLimit;
    private final Handler defaultHandler;
    private final Handler default404Handler = (request, responseStream) -> {
        responseStream.write((
                "HTTP/1.1 404 Not Found\r\n" +
                        "Content-Length: 0\r\n" +
                        "Connection: close\r\n" +
                        "\r\n"
        ).getBytes());
        responseStream.flush();
    };
    private final Handler default400Handler = (request, responseStream) -> {
        responseStream.write((
                "HTTP/1.1 400 Bad Request\r\n" +
                        "Content-Length: 0\r\n" +
                        "Connection: close\r\n" +
                        "\r\n"
        ).getBytes());
        responseStream.flush();
    };

    private final Map<String, Map<String, Handler>> handlers = new ConcurrentHashMap<>();


    public Server(int port, int threadPoolSize, int requestLimit, Handler defaultHandler) {
        this.port = port;
        this.threadPool = Executors.newFixedThreadPool(threadPoolSize);
        this.requestLimit = requestLimit;
        this.defaultHandler = defaultHandler;
    }

    public int getPort() {
        return port;
    }

    public void start() {
        try (final var serverSocket = new ServerSocket(port)) {
            System.out.println("Сервер запущен на порту " + port);
            while (true) {
                var clientSocket = serverSocket.accept();
                threadPool.submit(() -> handleClient(clientSocket));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void handleClient(Socket clientSocket) {
        try (
                clientSocket;
                final var in = new BufferedInputStream(clientSocket.getInputStream());
                final var out = new BufferedOutputStream(clientSocket.getOutputStream())
        ) {
            in.mark(requestLimit);
            final var buffer = new byte[requestLimit];
            final var readLength = in.read(buffer);

            final var requestLineEnd = indexOf(buffer, requestLineDelimiterBytes, 0, readLength);
            if (requestLineEnd == -1) {
                default400Handler.handle(null, out);
                return;
            }

            final var requestLine = new String(Arrays.copyOf(buffer, requestLineEnd)).split(" ");
            if (requestLine.length != 3) {
                default400Handler.handle(null, out);
                return;
            }

            final var method = requestLine[0];
            if (!validMethods.contains(method)) {
                default400Handler.handle(null, out);
                return;
            }
            System.out.println(method);

            final var rawPath = requestLine[1];
            if (!rawPath.startsWith("/")) {
                default400Handler.handle(null, out);
                return;
            }

            var uriBuilder = new URIBuilder(rawPath);
            final var path = uriBuilder.getPath();
            System.out.println(path);

            var params = uriBuilder.getQueryParams();
            final Map<String, String> queryParams = new HashMap<>();
            for (var param : params) {
                queryParams.put(param.getName(), param.getValue());
            }
            System.out.println(queryParams);

            final var headersStart = requestLineEnd + requestLineDelimiterBytes.length;
            final var headersEnd = indexOf(buffer, headersDelimiterBytes, headersStart, readLength);
            if (headersEnd == -1) {
                default400Handler.handle(null, out);
                return;
            }

            in.reset();
            in.skip(headersStart);

            final var headersBytes = in.readNBytes(headersEnd - headersStart);
            final var headers = getHeadersFromBytes(headersBytes);
            System.out.println(headers);

            var body = "";
            Map<String, List<String>> bodyParts = new LinkedHashMap<>();
            if (!method.equals(GET)) {
                in.skip(headersDelimiterBytes.length);
                final var contentLength = headers.get(CONTENT_LENGTH_HEADER);
                if (contentLength != null) {
                    final var length = Integer.parseInt(contentLength);
                    if (length > 0) {
                        final var bodyBytes = in.readNBytes(length);

                        body = new String(bodyBytes);
                        System.out.println(body);

                        var contentType = headers.get(CONTENT_TYPE_HEADER);
                        if (contentType != null) {
                            if (contentType.equalsIgnoreCase(X_WWW_FORM_URLENCODED)) {
                                bodyParts = getBodyPartsForUrlEncoded(body);
                            }
                        }
                        if (!bodyParts.isEmpty()) {
                            System.out.println(bodyParts);
                        }
                    }
                }
            }

            final var request = new Request(method, path, queryParams, headers, body, bodyParts);

            Handler handler = null;
            Map<String, Handler> methodHandlers = handlers.get(method);
            if (methodHandlers != null) {
                handler = methodHandlers.get(path);
            }

            if (handler != null) {
                handler.handle(request, out);
            } else if (defaultHandler != null && validPaths.contains(path)) {
                defaultHandler.handle(request, out);
            } else {
                default404Handler.handle(null, out);
            }
        } catch (IOException | URISyntaxException e) {
            e.printStackTrace();
        }
    }

    private Map<String, String> getHeadersFromBytes(byte[] headersBytes) {
        var headersLines = new String(headersBytes).split(new String(requestLineDelimiterBytes));
        Map<String, String> headers = new LinkedHashMap<>();

        for (var headerLine : headersLines) {
            int colonIndex = headerLine.indexOf(":");
            if (colonIndex > 0) {
                var name = headerLine.substring(0, colonIndex).trim();
                var value = headerLine.substring(colonIndex + 1).trim();

                headers.put(name, value);
            }
        }
        return headers;
    }

    private Map<String, List<String>> getBodyPartsForUrlEncoded(String body) {
        Map<String, List<String>> bodyParts = new LinkedHashMap<>();
        var pairs = WWWFormCodec.parse(body, StandardCharsets.UTF_8);

        for (var pair : pairs) {
            String key = pair.getName();
            String value = pair.getValue();

            bodyParts.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }
        return bodyParts;
    }

    private int indexOf(byte[] array, byte[] target, int start, int max) {
        outer:
        for (int i = start; i < max - target.length + 1; i++) {
            for (int j = 0; j < target.length; j++) {
                if (array[i + j] != target[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    public void addHandler(String method, String path, Handler handler) {
        handlers.computeIfAbsent(method, m -> new ConcurrentHashMap<>()).put(path, handler);
    }
}