package ru.netology;

import java.util.List;
import java.util.Map;

public record Request(
        String method,
        String path,
        Map<String, String> queryParams,
        List<String> headers,
        String body) {
}
