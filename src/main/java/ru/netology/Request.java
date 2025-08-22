package ru.netology;

import java.util.List;
import java.util.Map;

public record Request(
        String method,
        String path,
        Map<String, String> queryParams,
        Map<String, String> headers,
        String body,
        Map<String, List<String>> bodyParts) {
}
