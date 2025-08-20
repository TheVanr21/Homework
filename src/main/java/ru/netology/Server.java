package ru.netology;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
            "/events.js"
    );

    private final int port;
    private final ExecutorService threadPool;
    private final Handler defaultHandler;

    private final Map<String, Map<String, Handler>> handlers = new ConcurrentHashMap<>();


    public Server(int port, int poolSize, Handler defaultHandler) {
        this.port = port;
        this.threadPool = Executors.newFixedThreadPool(poolSize);
        this.defaultHandler = defaultHandler;
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
                final var in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                final var out = new BufferedOutputStream(clientSocket.getOutputStream())
        ) {
            final var requestLine = in.readLine();
            final var parts = requestLine.split(" ");

            if (parts.length != 3) {
                return;
            }

            final var method = parts[0];
            final var path = parts[1];

            final List<String> headers = new ArrayList<>();
            String headerLine;
            while ((headerLine = in.readLine()).isBlank()) {
                headers.add(headerLine);
            }

            var request = new Request(method, path, headers, "");

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
                out.write((
                        "HTTP/1.1 404 Not Found\r\n" +
                                "Content-Length: 0\r\n" +
                                "Connection: close\r\n" +
                                "\r\n"
                ).getBytes());
                out.flush();
                return;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void addHandler(String method, String path, Handler handler) {
        handlers.computeIfAbsent(method, m -> new ConcurrentHashMap<>()).put(path, handler);
    }
}