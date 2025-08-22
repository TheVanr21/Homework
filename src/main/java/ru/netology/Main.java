package ru.netology;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

public class Main {
    public static void main(String[] args) {
        var server = new Server(
                9999,
                64,
                4096,
                (request, responseStream) -> {
                    final var filePath = Path.of(".", "public", request.path());
                    final var mimeType = Files.probeContentType(filePath);

                    final var content = Files.readAllBytes(filePath);
                    sendResponse(responseStream, 200, "OK", mimeType, content);
                });

        server.addHandler("GET", "/classic.html", (request, responseStream) -> {
            final var filePath = Path.of(".", "public", request.path());
            final var mimeType = Files.probeContentType(filePath);

            final var template = Files.readString(filePath);
            final var content = template.replace(
                    "{time}",
                    LocalDateTime.now().toString()
            ).getBytes();
            sendResponse(responseStream, 200, "OK", mimeType, content);
        });

        server.addHandler("GET", "/form.html", (request, responseStream) -> {
            final var filePath = Path.of(".", "public", request.path());
            final var mimeType = Files.probeContentType(filePath);

            final var template = Files.readString(filePath);
            final var content = template
                    .replace("{host}", "localhost")
                    .replace("{port}", String.valueOf(server.getPort()))
                    .getBytes();
            sendResponse(responseStream, 200, "OK", mimeType, content);
        });

        server.addHandler("GET", "/multipart.html", (request, responseStream) -> {
            final var filePath = Path.of(".", "public", request.path());
            final var mimeType = Files.probeContentType(filePath);

            final var template = Files.readString(filePath);
            final var content = template
                    .replace("{host}", "localhost")
                    .replace("{port}", String.valueOf(server.getPort()))
                    .getBytes();
            sendResponse(responseStream, 200, "OK", mimeType, content);
        });

        server.addHandler("GET", "/messages", (request, responseStream) -> {
            final var filePath = Path.of(".", "public", "/response.html");
            final var mimeType = Files.probeContentType(filePath);

            final var template = Files.readString(filePath);
            final var content = template
                    .replace("{params}", request.queryParams().toString())
                    .getBytes();
            sendResponse(responseStream, 200, "OK", mimeType, content);
        });

        server.addHandler("POST", "/get-value", (request, responseStream) -> {
            final var filePath = Path.of(".", "public", "/response.html");
            final var mimeType = Files.probeContentType(filePath);

            final var template = Files.readString(filePath);
            var replacement = new StringBuilder();
            for (var part : request.bodyParts().entrySet()) {
                replacement.append(part.getKey()).append(" -> ").append(part.getValue()).append("<br>");
            }
            final var content = template
                    .replace("{params}", replacement)
                    .getBytes();
            sendResponse(responseStream, 200, "OK", mimeType, content);
        });

        server.start();
    }

    private static void sendResponse(BufferedOutputStream responseStream,
                                     int statusCode,
                                     String statusMessage,
                                     String contentType,
                                     byte[] content) throws IOException {
        responseStream.write((
                "HTTP/1.1 " + statusCode + " " + statusMessage + "\r\n" +
                        "Content-Type: " + contentType + "\r\n" +
                        "Content-Length: " + content.length + "\r\n" +
                        "Connection: close\r\n" +
                        "\r\n"
        ).getBytes());
        responseStream.write(content);
        responseStream.flush();
    }
}