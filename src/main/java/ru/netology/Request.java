package ru.netology;

import java.util.List;

public record Request(
        String method,
        String path,
        List<String> headers,
        String body) {
}
