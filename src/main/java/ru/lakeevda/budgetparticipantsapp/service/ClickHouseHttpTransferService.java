package ru.lakeevda.budgetparticipantsapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import ru.lakeevda.budgetparticipantsapp.config.ConfigLoader;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Сервис переноса данных из PostgreSQL в ClickHouse через HTTP-интерфейс.
 * Использует ClickHouse HTTP API для вставки данных.
 *
 * @author lakeev-da
 * @since 2026-11-10
 */
public class ClickHouseHttpTransferService {

    private static final Logger log = Logger.getLogger(ClickHouseHttpTransferService.class.getName());

    private static final int HTTP_TIMEOUT_MS = 30000;
    private static final int BATCH_SIZE = 1000;

    private final ObjectMapper mapper = new ObjectMapper();
    private final String httpUrl;
    private final String database;
    private final String table;
    private final String rawDir;

    /**
     * Конструктор сервиса HTTP-переноса в ClickHouse.
     *
     * @param httpUrl URL HTTP-интерфейса ClickHouse
     * @param database имя базы данных ClickHouse
     * @param table имя таблицы
     * @param rawDir директория для сырых JSON-файлов
     */
    public ClickHouseHttpTransferService(String httpUrl, String database, String table, String rawDir) {
        this.httpUrl = httpUrl;
        this.database = database;
        this.table = table;
        this.rawDir = rawDir;
    }

    /**
     * Проверяет, включён ли HTTP-перенос в ClickHouse.
     *
     * @return true если перенос включён, false иначе
     */
    public boolean isEnabled() {
        return Boolean.parseBoolean(ConfigLoader.getProperty("clickhouse.http.enabled", "false"));
    }

    /**
     * Переносит данные из JSON-файлов в ClickHouse через HTTP-интерфейс.
     *
     * @param from   начальная дата периода
     * @param to     конечная дата периода
     * @throws IOException при ошибках сети или файловой системы
     */
    public void transfer(LocalDate from, LocalDate to) throws IOException {
        log.info("Запуск HTTP-переноса данных в ClickHouse: " + from + ".." + to);

        File rawDir = new File(this.rawDir);
        if (!rawDir.exists()) {
            log.warning("Директория сырых данных не существует: " + rawDir.getAbsolutePath());
            return;
        }

        List<File> pageFiles = listJsonFiles(rawDir);
        if (pageFiles.isEmpty()) {
            log.info("Нет JSON-файлов для переноса в директории " + rawDir.getAbsolutePath());
            return;
        }

        log.info("Найдено JSON-файлов для переноса: " + pageFiles.size());

        try (CloseableHttpClient http = HttpClients.custom()
                .setDefaultRequestConfig(org.apache.http.client.config.RequestConfig.custom()
                        .setConnectTimeout(HTTP_TIMEOUT_MS)
                        .setSocketTimeout(HTTP_TIMEOUT_MS)
                        .build())
                .build()) {

            for (File pageFile : pageFiles) {
                transferPage(http, pageFile, from, to);
            }

            log.info("HTTP-перенос завершён");
        }
    }

    /**
     * Переносит данные одной страницы в ClickHouse через HTTP.
     *
     * @param http   HTTP-клиент
     * @param pageFile файл страницы
     * @param from   начальная дата периода
     * @param to     конечная дата периода
     * @throws IOException при ошибках сети или JSON-обработки
     */
    private void transferPage(CloseableHttpClient http, File pageFile, LocalDate from, LocalDate to)
            throws IOException {

        log.fine("Перенос страницы: " + pageFile.getName());

        try {
            JsonNode root = mapper.readTree(pageFile);
            JsonNode items = root.has("data") ? root.get("data") : root;

            if (!items.isArray()) {
                log.warning("Некорректная структура JSON в " + pageFile.getName()
                        + ": поле 'data' отсутствует, а корень не является массивом");
                return;
            }

            List<Map<String, String>> rows = new ArrayList<>(items.size());
            for (JsonNode item : items) {
                Map<String, String> flat = flattenJson(item);
                flat.put("last_update_from", from.toString());
                flat.put("last_update_to", to.toString());
                rows.add(flat);
            }

            if (rows.isEmpty()) {
                log.fine("Нет записей для переноса в " + pageFile.getName());
                return;
            }

            log.info("Перенос строк из " + pageFile.getName() + ": " + rows.size());

            String jsonBody = mapper.writeValueAsString(rows);
            sendToClickHouse(http, jsonBody);

        } catch (IOException e) {
            log.log(Level.SEVERE, "Ошибка переноса страницы " + pageFile.getName(), e);
            throw e;
        }
    }

    /**
     * Отправляет JSON-данные в ClickHouse через HTTP POST.
     *
     * @param http   HTTP-клиент
     * @param jsonBody тело запроса в формате JSON массива объектов
     * @throws IOException при ошибках сети
     */
    private void sendToClickHouse(CloseableHttpClient http, String jsonBody) throws IOException {
        HttpPost post = new HttpPost(httpUrl + "/insert/" + database + "/" + table);
        post.addHeader("Content-Type", "application/json");
        post.addHeader("X-ClickHouse-User", ConfigLoader.getProperty("clickhouse.username", "default"));

        HttpEntity entity = new StringEntity(jsonBody, StandardCharsets.UTF_8);
        post.setEntity(entity);

        try (CloseableHttpResponse response = http.execute(post)) {
            int status = response.getStatusLine().getStatusCode();
            if (status != 200 && status != 202) {
                String errorBody = extractError(response);
                throw new IOException("HTTP-статус " + status + " для ClickHouse: " + errorBody);
            }

            long rowsInserted = extractRowsInserted(response);
            log.info("ClickHouse HTTP: вставлено строк " + rowsInserted);
        }
    }

    /**
     * Извлекает количество вставленных строк из ответа ClickHouse.
     *
     * @param response ответ от ClickHouse
     * @return число вставленных строк
     */
    private long extractRowsInserted(CloseableHttpResponse response) {
        try {
            HttpEntity entity = response.getEntity();
            if (entity != null) {
                String body = EntityUtils.toString(entity, StandardCharsets.UTF_8);
                EntityUtils.consume(entity);
                JsonNode result = mapper.readTree(body);
                JsonNode quota = result.get("quota_key");
                if (quota != null && quota.isObject()) {
                    JsonNode inserted = quota.get("inserted");
                    if (inserted != null && inserted.isNumber()) {
                        return inserted.asLong();
                    }
                }
            }
        } catch (IOException e) {
            log.fine("Не удалось извлечь количество вставленных строк: " + e.getMessage());
        }
        return 0;
    }

    /**
     * Извлекает сообщение об ошибке из ответа ClickHouse.
     *
     * @param response ответ от ClickHouse
     * @return сообщение об ошибке
     */
    private String extractError(CloseableHttpResponse response) {
        try {
            HttpEntity entity = response.getEntity();
            if (entity != null) {
                String body = EntityUtils.toString(entity, StandardCharsets.UTF_8);
                EntityUtils.consume(entity);
                return body;
            }
        } catch (IOException e) {
            log.fine("Ошибка чтения ответа: " + e.getMessage());
        }
        return "";
    }

    /**
     * Преобразует JSON-объект в плоскую структуру Map.
     *
     * @param node узел JSON
     * @return плоская структура данных
     */
    private Map<String, String> flattenJson(JsonNode node) {
        Map<String, String> result = new java.util.HashMap<>();

        for (JsonNode field : node) {
            String key = field.asText();
            JsonNode value = field.get("value");
            if (value != null) {
                result.put(key, value.asText());
            }
        }

        return result;
    }

    /**
     * Получает список JSON-файлов из директории.
     *
     * @param dir директория
     * @return список файлов
     */
    private List<File> listJsonFiles(File dir) {
        List<File> files = new ArrayList<>();
        File[] rawFiles = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (rawFiles != null) {
            for (File f : rawFiles) {
                files.add(f);
            }
        }
        return files;
    }
}
