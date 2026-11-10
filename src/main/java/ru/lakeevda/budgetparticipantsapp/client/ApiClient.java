package ru.lakeevda.budgetparticipantsapp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import ru.lakeevda.budgetparticipantsapp.config.ConfigLoader;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Клиент для скачивания страниц API.
 * Скачивает все страницы API за указанный период и сохраняет сырые ответы в файлы.
 *
 * @author lakeev-da
 * @since 2026-11-06
 */
public class ApiClient {

    private static final Logger log = Logger.getLogger(ApiClient.class.getName());

    /** Общее число страниц ещё неизвестно. */
    private static final int UNKNOWN_TOTAL_PAGES = -1;

    /** API сообщил, что за период страниц нет. */
    private static final int NO_PAGES = 0;

    private final String urlTemplate;
    private final String itemsKey;
    private final String totalPagesKey;
    private final DateTimeFormatter dateFormat;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Создаёт нового клиента API.
     * Конфигурация загружается из application.properties.
     */
    public ApiClient() {
        this.urlTemplate = ConfigLoader.getProperty("api.url");
        this.itemsKey = ConfigLoader.getProperty("api.itemsKey", "");
        this.totalPagesKey = ConfigLoader.getProperty("api.totalPagesKey", "");
        this.dateFormat = DateTimeFormatter.ofPattern(
                ConfigLoader.getProperty("api.dateFormat", "yyyy-MM-dd"));
    }

    /**
     * Скачивает все страницы API за указанный период.
     * Выполняет пагинацию до получения всех записей.
     *
     * @param from начальная дата периода
     * @param to конечная дата периода
     * @param rawDir директория для хранения сырых файлов
     * @return список скачанных файлов
     * @throws IOException при ошибках сети или файловой системы
     */
    public List<File> fetchAllPages(LocalDate from, LocalDate to, File rawDir) throws IOException {
        log.info(String.format("Скачивание всех страниц за период %s..%s", from, to));
        List<File> pages;
        try (CloseableHttpClient http = HttpClients.createDefault()) {
            pages = fetchPages(http, from, to, rawDir);
        }
        log.info("Всего скачано страниц: " + pages.size());
        return pages;
    }

    /**
     * Выполняет пагинацию: скачивает страницы, пока не будет достигнута последняя.
     *
     * @param http   HTTP-клиент
     * @param from   начальная дата периода
     * @param to     конечная дата периода
     * @param rawDir директория для хранения сырых файлов
     * @return список скачанных файлов
     * @throws IOException при ошибках сети или файловой системы
     */
    private List<File> fetchPages(CloseableHttpClient http, LocalDate from, LocalDate to,
                                  File rawDir) throws IOException {
        List<File> files = new ArrayList<>();
        int page = 1;
        int totalPages = UNKNOWN_TOTAL_PAGES;
        boolean totalPagesResolved = false;

        while (true) {
            URI uri = buildPageUri(from, to, page);
            String body = downloadPage(http, uri);
            JsonNode root = mapper.readTree(body);
            JsonNode items = pickItems(root);

            if (!totalPagesResolved) {
                totalPages = resolveTotalPages(root);
                totalPagesResolved = true;
            }
            if (totalPages == NO_PAGES) {
                break;
            }

            File out = savePage(rawDir, from, to, page, body);
            files.add(out);
            logDownloaded(out, items, page, totalPages);

            if (shouldStopPagination(page, totalPages, items)) {
                break;
            }
            page++;
        }
        return files;
    }

    /**
     * Скачивает одну страницу API и возвращает тело ответа.
     *
     * @param http HTTP-клиент
     * @param uri  адрес страницы
     * @return тело ответа в виде строки
     * @throws IOException при ошибках сети или при HTTP-статусе, отличном от 200
     */
    private String downloadPage(CloseableHttpClient http, URI uri) throws IOException {
        HttpGet get = new HttpGet(uri);
        try (CloseableHttpResponse resp = http.execute(get)) {
            int status = resp.getStatusLine().getStatusCode();
            if (status != 200) {
                throw new IOException("HTTP-статус " + status + " для " + uri);
            }
            return EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Формирует адрес страницы по шаблону из конфигурации.
     *
     * @param from начальная дата периода
     * @param to   конечная дата периода
     * @param page номер страницы
     * @return адрес страницы
     */
    private URI buildPageUri(LocalDate from, LocalDate to, int page) {
        return URI.create(String.format(urlTemplate,
                dateFormat.format(from), dateFormat.format(to), page));
    }

    /**
     * Определяет общее число страниц по ответу API.
     * Если ключ не задан в конфигурации, возвращает {@link #UNKNOWN_TOTAL_PAGES}.
     *
     * @param root корневой узел JSON
     * @return общее число страниц, {@link #NO_PAGES}, если страниц нет,
     *         либо {@link Integer#MAX_VALUE}, если значение не найдено или не является числом
     */
    private int resolveTotalPages(JsonNode root) {
        if (totalPagesKey.isEmpty()) {
            return UNKNOWN_TOTAL_PAGES;
        }
        JsonNode totalPagesNode = root.get(totalPagesKey);
        if (totalPagesNode == null || !totalPagesNode.isInt()) {
            return Integer.MAX_VALUE;
        }
        int totalPages = totalPagesNode.asInt();
        if (totalPages <= 0) {
            log.info("За период страниц нет (pageCount=" + totalPagesNode.asText() + ")");
            return NO_PAGES;
        }
        return totalPages;
    }

    /**
     * Сохраняет тело ответа страницы в файл.
     *
     * @param rawDir директория для хранения сырых файлов
     * @param from   начальная дата периода
     * @param to     конечная дата периода
     * @param page   номер страницы
     * @param body   тело ответа
     * @return сохранённый файл
     * @throws IOException при ошибках записи файла
     */
    private File savePage(File rawDir, LocalDate from, LocalDate to, int page,
                          String body) throws IOException {
        File out = new File(rawDir, pageFileName(from, to, page));
        Files.write(out.toPath(), body.getBytes(StandardCharsets.UTF_8));
        return out;
    }

    /**
     * Формирует имя файла страницы.
     *
     * @param from начальная дата периода
     * @param to   конечная дата периода
     * @param page номер страницы
     * @return имя файла
     */
    private static String pageFileName(LocalDate from, LocalDate to, int page) {
        return String.format("page_%s_%s_%05d.json", from, to, page);
    }

    /**
     * Пишет в лог информацию о скачанной странице.
     *
     * @param out        сохранённый файл
     * @param items      массив записей страницы
     * @param page       номер страницы
     * @param totalPages общее число страниц
     */
    private void logDownloaded(File out, JsonNode items, int page, int totalPages) {
        log.info("Скачано: " + out.getName()
                + " (записей: " + items.size() + ", страница " + page
                + (totalPages > 0 ? "/" + totalPages : "") + ")");
    }

    /**
     * Проверяет, нужно ли остановить пагинацию.
     *
     * @param page       номер текущей страницы
     * @param totalPages общее число страниц
     * @param items      массив записей текущей страницы
     * @return {@code true}, если пагинацию следует остановить
     */
    private static boolean shouldStopPagination(int page, int totalPages, JsonNode items) {
        if (totalPages > 0 && page >= totalPages) {
            return true;
        }
        return totalPages < 0 && items.isEmpty();
    }

    /**
     * Извлекает массив записей из JSON-ответа.
     * Ищет массив либо в указанном ключе, либо в корне, либо первый массив в объекте.
     *
     * @param root корневой узел JSON
     * @return массив записей
     * @throws IllegalStateException если массив не найден
     */
    JsonNode pickItems(JsonNode root) {
        if (!itemsKey.isEmpty()) {
            JsonNode node = root.get(itemsKey);
            if (node == null || !node.isArray()) {
                throw new IllegalStateException("Поле '" + itemsKey + "' не найдено или не является массивом");
            }
            return node;
        }
        if (root.isArray()) return root;
        for (JsonNode f : root) {
            if (f.isArray()) return f;
        }
        throw new IllegalStateException("В ответе нет массива записей");
    }
}
