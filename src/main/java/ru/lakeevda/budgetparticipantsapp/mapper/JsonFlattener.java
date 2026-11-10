package ru.lakeevda.budgetparticipantsapp.mapper;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;
import java.util.Map.Entry;

/**
 * Разворачивает JSON-запись в плоскую структуру.
 *
 * @author lakeev-da
 * @since 2026-11-06
 */
public final class JsonFlattener {

    /**
     * Частный конструктор.
     */
    private JsonFlattener() {
    }

    /**
     * Разворачивает JSON-узел в плоскую карту "ключ -> значение".
     *
     * @param node JSON-узел для разворачивания
     * @return плоская карта с развёрнутыми данными
     */
    public static Map<String, String> flatten(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        flatten("", node, out);
        return out;
    }

    /**
     * Рекурсивно разворачивает JSON-узел.
     * Обрабатывает объекты, массивы и primitive значения.
     *
     * @param path текущий путь к узлу
     * @param node JSON-узел для обработки
     * @param out  результирующая плоская карта
     */
    private static void flatten(String path, JsonNode node, Map<String, String> out) {
        if (node.isObject()) {
            if (node.isEmpty()) return; // пустой объект — не сохраняем
            for (Iterator<Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Entry<String, JsonNode> e = it.next();
                flatten(append(path, e.getKey()), e.getValue(), out);
            }
        } else if (node.isArray()) {
            if (node.isEmpty()) return; // пустой массив — не сохраняем
            List<JsonNode> items = sortedElements(node);
            for (int i = 0; i < items.size(); i++) {
                flatten(path + "_" + i, items.get(i), out);
            }
        } else if (node.isNull()) {
            return; // null-значение — не сохраняем
        } else {
            out.put(col(path), node.asText());
        }
    }

    /**
     * Возвращает элементы массива в детерминированном порядке.
     *
     * @param array JSON-массив
     * @return элементы, отсортированные по каноническому представлению
     */
    private static List<JsonNode> sortedElements(JsonNode array) {
        List<Entry<String, JsonNode>> keyed = new ArrayList<>(array.size());
        for (JsonNode item : array) {
            keyed.add(new AbstractMap.SimpleEntry<>(canonical(item), item));
        }
        keyed.sort(Entry.comparingByKey());
        List<JsonNode> items = new ArrayList<>(keyed.size());
        for (Entry<String, JsonNode> entry : keyed) {
            items.add(entry.getValue());
        }
        return items;
    }

    /**
     * Строит каноническое представление JSON-узла с отсортированными ключами
     * и элементами массивов.
     *
     * @param node JSON-узел
     * @return каноническая строка
     */
    private static String canonical(JsonNode node) {
        if (node.isObject()) {
            return canonicalObject(node);
        }
        if (node.isArray()) {
            return canonicalArray(node);
        }
        return node.toString();
    }

    /**
     * Строит каноническое представление JSON-объекта с отсортированными ключами.
     *
     * @param node JSON-объект
     * @return строка вида {@code {"key":value,...}}
     */
    private static String canonicalObject(JsonNode node) {
        List<String> members = new ArrayList<>(node.size());
        for (String key : sortedFieldNames(node)) {
            members.add(quote(key) + ':' + canonical(node.get(key)));
        }
        return join(members, '{', '}');
    }

    /**
     * Строит каноническое представление JSON-массива с отсортированными
     * каноническими представлениями элементов.
     *
     * @param node JSON-массив
     * @return строка вида {@code [element,...]}
     */
    private static String canonicalArray(JsonNode node) {
        List<String> parts = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            parts.add(canonical(item));
        }
        Collections.sort(parts);
        return join(parts, '[', ']');
    }

    /**
     * Возвращает имена полей объекта в алфавитном порядке.
     *
     * @param node JSON-объект
     * @return отсортированный список имён полей
     */
    private static List<String> sortedFieldNames(JsonNode node) {
        List<String> keys = new ArrayList<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            keys.add(it.next());
        }
        Collections.sort(keys);
        return keys;
    }

    /**
     * Объединяет части канонической строки через запятую, обрамляя их
     * открывающей и закрывающей скобками.
     *
     * @param parts части строки
     * @param open  открывающая скобка
     * @param close закрывающая скобка
     * @return строка вида {@code open + part + "," + ... + close}
     */
    private static String join(List<String> parts, char open, char close) {
        StringBuilder sb = new StringBuilder().append(open);
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(parts.get(i));
        }
        return sb.append(close).toString();
    }

    /**
     * Экранирует ключ объекта для канонического представления.
     *
     * @param key ключ
     * @return ключ в кавычках
     */
    private static String quote(String key) {
        return '"' + key.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /**
     * Добавляет ключ к пути.
     *
     * @param path исходный путь
     * @param key  ключ для добавления
     * @return новый путь
     */
    private static String append(String path, String key) {
        return path.isEmpty() ? key : path + "_" + key;
    }

    /**
     * Приводит имя колонки к валидному для Postgres виду.
     *
     * @param path путь к полю
     * @return валидное имя колонки
     */
    static String col(String path) {
        return path.toLowerCase().replaceAll("[^a-z0-9_]", "_");
    }
}
