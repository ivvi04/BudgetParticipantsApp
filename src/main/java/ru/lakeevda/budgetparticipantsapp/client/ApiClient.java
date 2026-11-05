package ru.lakeevda.budgetparticipantsapp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Клиент для скачивания страниц API.
 * Скачивает все страницы API за указанный период и сохраняет сырые ответы в файлы.
 */
public class ApiClient {
    private static final Logger log = Logger.getLogger(ApiClient.class.getName());

    private final String urlTemplate;
    private final String itemsKey;
    private final String totalPagesKey;
    private final DateTimeFormatter dateFormat;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Создаёт нового клиента API.
     *
     * @param cfg свойства конфигурации
     */
    public ApiClient(Properties cfg) {
        this.urlTemplate = cfg.getProperty("api.url");
        this.itemsKey = cfg.getProperty("api.itemsKey", "");
        this.totalPagesKey = cfg.getProperty("api.totalPagesKey", "");
        this.dateFormat = DateTimeFormatter.ofPattern(
                cfg.getProperty("api.dateFormat", "yyyy-MM-dd"));
    }

    /**
     * Скачивает все страницы API за указанный период.
     *
     * @param from начальная дата
     * @param to конечная дата
     * @param rawDir директория для хранения сырых файлов
     * @return список скачанных файлов
     * @throws IOException при ошибках сети или файловой системы
     */
    public List<File> fetchAllPages(LocalDate from, LocalDate to, File rawDir) throws IOException {
        log.info(String.format("Fetching all pages for period %s..%s", from, to));
        List<File> result = new ArrayList<>();
        try (CloseableHttpClient http = HttpClients.createDefault()) {
            int page = 1;
            int totalPages = -1; // неизвестно, пока не спарсим первый ответ
            while (true) {
                URI uri = URI.create(String.format(urlTemplate,
                        dateFormat.format(from), dateFormat.format(to), page));
                HttpGet get = new HttpGet(uri);
                try (CloseableHttpResponse resp = http.execute(get)) {
                    int status = resp.getStatusLine().getStatusCode();
                    if (status != 200) {
                        throw new IOException("HTTP " + status + " for " + uri);
                    }
                    String body = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
                    JsonNode root = mapper.readTree(body);

                    JsonNode items = pickItems(root);
                    if (totalPages < 0 && !totalPagesKey.isEmpty()) {
                        JsonNode tp = root.get(totalPagesKey);
                        totalPages = (tp != null && tp.isInt()) ? tp.asInt() : Integer.MAX_VALUE;
                        if (totalPages <= 0) {
                            log.info("No pages for the period (pageCount="
                                    + (tp == null ? "null" : tp.asText()) + ")");
                            break;
                        }
                    }

                    File out = new File(rawDir,
                            String.format("page_%s_%s_%05d.json", from, to, page));
                    Files.write(out.toPath(), body.getBytes(StandardCharsets.UTF_8));
                    result.add(out);
                    log.info("Downloaded: " + out.getName()
                            + " (" + items.size() + " records, page " + page
                            + (totalPages > 0 ? "/" + totalPages : "") + ")");

                    // Условия остановки пагинации
                    if (totalPages > 0 && page >= totalPages) break;
                    if (totalPages < 0 && items.size() == 0) break;
                    page++;
                }
            }
        }
        log.info("Total pages downloaded: " + result.size());
        return result;
    }

    /**
     * Достаёт массив записей из JSON-ответа.
     * Ищет массив либо в указанном ключе, либо в корне, либо первый массив в объекте.
     *
     * @param root корневой узел JSON
     * @return массив записей
     */
    JsonNode pickItems(JsonNode root) {
        if (!itemsKey.isEmpty()) {
            JsonNode node = root.get(itemsKey);
            if (node == null || !node.isArray()) {
                throw new IllegalStateException("Field '" + itemsKey + "' not found or not array");
            }
            return node;
        }
        if (root.isArray()) return root;
        for (JsonNode f : root) {
            if (f.isArray()) return f;
        }
        throw new IllegalStateException("No array of records in response");
    }
}
