package ru.lakeevda.budgetparticipantsapp.mapper;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Разворачивает JSON-запись в плоскую структуру "имя колонки -> значение".
 * Вложенные объекты разворачиваются через подчёркивание: info.fullName -> info_fullname.
 * Массивы не пишутся целиком как json/jsonb: каждый элемент получает
 * свою колонку с индексом — authorities_0_authoritycode, heads_2_fio и т.д.
 */
public final class JsonFlattener {

    private JsonFlattener() {
    }

    public static Map<String, String> flatten(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        flatten("", node, out);
        return out;
    }

    private static void flatten(String path, JsonNode node, Map<String, String> out) {
        if (node.isObject()) {
            if (node.isEmpty()) return; // пустой объект — не сохраняем
            for (Iterator<Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Entry<String, JsonNode> e = it.next();
                flatten(append(path, e.getKey()), e.getValue(), out);
            }
        } else if (node.isArray()) {
            if (node.isEmpty()) return; // пустой массив — не сохраняем
            for (int i = 0; i < node.size(); i++) {
                flatten(path + "_" + i, node.get(i), out);
            }
        } else if (node.isNull()) {
            return; // null-значение — не сохраняем
        } else {
            out.put(col(path), node.asText());
        }
    }


    private static String append(String path, String key) {
        return path.isEmpty() ? key : path + "_" + key;
    }

    /**
     * Приводит имя колонки к валидному для Postgres виду.
     */
    static String col(String path) {
        return path.toLowerCase().replaceAll("[^a-z0-9_]", "_");
    }
}

