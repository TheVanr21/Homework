package ru.netology;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
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
    private final String CONTENT_DISPOSITION_HEADER = "Content-Disposition";
    private final String MULTIPART_FORM_DATA = "multipart/form-data";

    private final String BOUNDARY = "boundary";

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

            final var path = requestLine[1];
            if (!path.startsWith("/")) {
                default400Handler.handle(null, out);
                return;
            }
            System.out.println(path);

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
                            if (contentType.toLowerCase().startsWith(MULTIPART_FORM_DATA)) {
                                var boundaryIndex = contentType.indexOf(BOUNDARY + "=");
                                if (boundaryIndex != -1) {
                                    var boundary = "--" + contentType.substring(boundaryIndex + BOUNDARY.length() + 1);
                                    bodyParts = getBodyPartsForMultipartFormData(body, boundary);
                                }
                            }
                        }
                        if (!bodyParts.isEmpty()) {
                            System.out.println(bodyParts);
                        }
                    }
                }
            }

            final var request = new Request(method, path, headers, body, bodyParts);

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
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Map<String, String> getHeadersFromBytes(byte[] headersBytes) {
        var headersLines = new String(headersBytes).split(new String(requestLineDelimiterBytes));
        Map<String, String> headers = new LinkedHashMap<>();

        for (var headerLine : headersLines) {
            int colonIndex = headerLine.indexOf(":");
            if (colonIndex != -1) {
                var name = headerLine.substring(0, colonIndex).trim();
                var value = headerLine.substring(colonIndex + 1).trim();

                headers.put(name, value);
            }
        }
        return headers;
    }

    private Map<String, List<String>> getBodyPartsForMultipartFormData(String body, String boundary) {
        Map<String, List<String>> bodyParts = new LinkedHashMap<>();

        var splitParts = body.split(boundary);
        for (var part : splitParts) {
            if (part.isBlank() || part.startsWith("--")) {
                continue;
            }

            var sections = part.split(new String(headersDelimiterBytes));
            if (sections.length < 2) {
                continue;
            }

            var headersBlock = sections[0].trim();
            var content = sections[1].trim();

            var headers = headersBlock.split(new String(requestLineDelimiterBytes));
            String name = null;
            String filename = null;
            String contentType = null;
            for (var header : headers) {
                if (header.startsWith(CONTENT_DISPOSITION_HEADER)) {
                    name = extractParameter(header, "name");
                    if (header.contains("filename=")) {
                        filename = extractParameter(header, "filename");
                    }
                }
                if (header.startsWith(CONTENT_TYPE_HEADER)) {
                    contentType = header;
                }
            }
            if (filename != null && contentType != null && name != null) {
                bodyParts.computeIfAbsent(name, k -> new ArrayList<>()).add("FILE: " + filename + " " + contentType + " => " + content);
            } else if (name != null) {
                bodyParts.computeIfAbsent(name, k -> new ArrayList<>()).add(content);
            }
        }
        return bodyParts;
    }

    private String extractParameter(String header, String parameter) {
        int headerIndex = header.indexOf(parameter + "=");
        if (headerIndex == -1) {
            return null;
        }

        int start = header.indexOf('"', headerIndex);
        int end = header.indexOf('"', start + 1);

        if (start != -1 && end != -1) {
            return header.substring(start + 1, end);
        }
        return null;
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