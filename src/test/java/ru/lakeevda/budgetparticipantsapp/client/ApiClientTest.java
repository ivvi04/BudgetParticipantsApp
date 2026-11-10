package ru.lakeevda.budgetparticipantsapp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.junit.Assert.*;

/**
 * Тесты для {@link ApiClient}.
 * Проверяют извлечение массива записей из ответа API.
 *
 * @author lakeev-da
 * @since 2026-11-06
 */
public class ApiClientTest {

    private static final LocalDate FROM = LocalDate.of(2024, 1, 1);
    private static final LocalDate TO = LocalDate.of(2024, 1, 31);

    private final ObjectMapper mapper = new ObjectMapper();
    private ApiClient client;

    /**
     * Временная директория для проверки сохранения страниц.
     */
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    /**
     * Создаёт клиента с конфигурацией из classpath.
     */
    @Before
    public void setUp() {
        client = new ApiClient();
    }

    /**
     * Проверяет извлечение массива из настроенного поля data.
     *
     * @throws Exception при ошибках парсинга
     */
    @Test
    public void picksConfiguredItemsArray() throws Exception {
        JsonNode root = mapper.readTree("{\"data\":[{\"id\":\"1\"},{\"id\":\"2\"}],"
                + "\"pageCount\":1}");
        JsonNode items = client.pickItems(root);

        assertEquals(2, items.size());
        assertEquals("1", items.get(0).get("id").asText());
        assertSame(items, root.get("data"));
    }

    /**
     * Проверяет исключение, если настроенное поле отсутствует.
     *
     * @throws Exception при ошибках парсинга
     */
    @Test(expected = IllegalStateException.class)
    public void throwsWhenItemsFieldMissing() throws Exception {
        client.pickItems(mapper.readTree("{\"other\":[]}"));
    }

    /**
     * Проверяет исключение, если настроенное поле не является массивом.
     *
     * @throws Exception при ошибках парсинга
     */
    @Test(expected = IllegalStateException.class)
    public void throwsWhenItemsFieldIsNotArray() throws Exception {
        client.pickItems(mapper.readTree("{\"data\":{\"id\":1}}"));
    }

    /**
     * Проверяет исключение для корневого массива при заданном itemsKey.
     *
     * @throws Exception при ошибках парсинга
     */
    @Test(expected = IllegalStateException.class)
    public void ignoresRootArrayWhenItemsKeyConfigured() throws Exception {
        client.pickItems(mapper.readTree("[{\"id\":1}]"));
    }

    /**
     * Проверяет, что без настроенного itemsKey берётся корневой массив.
     *
     * @throws Exception при ошибках парсинга
     */
    @Test
    public void picksRootArrayWhenItemsKeyEmpty() throws Exception {
        ApiClient noKey = new ApiClient("", "", "", DateTimeFormatter.ISO_DATE);
        JsonNode root = mapper.readTree("[{\"id\":1}]");

        assertSame(root, noKey.pickItems(root));
    }

    /**
     * Проверяет, что без настроенного itemsKey берётся первый массив объекта.
     *
     * @throws Exception при ошибках парсинга
     */
    @Test
    public void picksFirstArrayWhenItemsKeyEmpty() throws Exception {
        ApiClient noKey = new ApiClient("", "", "", DateTimeFormatter.ISO_DATE);
        JsonNode root = mapper.readTree("{\"page\":1,\"data\":[{\"id\":1}]}");

        assertSame(root.get("data"), noKey.pickItems(root));
    }

    /**
     * Проверяет исключение, если без itemsKey в ответе нет массива.
     *
     * @throws Exception при ошибках парсинга
     */
    @Test(expected = IllegalStateException.class)
    public void throwsWhenNoArrayAndItemsKeyEmpty() throws Exception {
        ApiClient noKey = new ApiClient("", "", "", DateTimeFormatter.ISO_DATE);
        noKey.pickItems(mapper.readTree("{\"page\":1}"));
    }

    /**
     * Проверяет чтение общего числа страниц из ответа.
     *
     * @throws Exception при ошибках парсинга
     */
    @Test
    public void resolvesTotalPages() throws Exception {
        assertEquals(5, client.resolveTotalPages(mapper.readTree("{\"pageCount\":5}")));
        assertEquals(Integer.MAX_VALUE, client.resolveTotalPages(mapper.readTree("{}")));
        assertEquals(Integer.MAX_VALUE, client.resolveTotalPages(mapper.readTree("{\"pageCount\":\"5\"}")));
        assertEquals(0, client.resolveTotalPages(mapper.readTree("{\"pageCount\":0}")));
        assertEquals(0, client.resolveTotalPages(mapper.readTree("{\"pageCount\":-3}")));
    }

    /**
     * Проверяет, что без настроенного ключа число страниц неизвестно.
     *
     * @throws Exception при ошибках парсинга
     */
    @Test
    public void returnsUnknownTotalPagesWhenKeyEmpty() throws Exception {
        ApiClient noKey = new ApiClient("", "", "", DateTimeFormatter.ISO_DATE);

        assertEquals(-1, noKey.resolveTotalPages(mapper.readTree("{\"pageCount\":5}")));
    }

    /**
     * Проверяет условия остановки пагинации.
     *
     * @throws Exception при ошибках парсинга
     */
    @Test
    public void stopsPaginationOnLastPageOrEmptyUnknownPage() throws Exception {
        JsonNode items = mapper.readTree("[{\"id\":1}]");
        JsonNode empty = mapper.readTree("[]");

        assertFalse(ApiClient.shouldStopPagination(1, 3, items));
        assertTrue(ApiClient.shouldStopPagination(3, 3, items));
        assertTrue(ApiClient.shouldStopPagination(4, 3, items));
        assertTrue(ApiClient.shouldStopPagination(1, -1, empty));
        assertFalse(ApiClient.shouldStopPagination(1, -1, items));
    }

    /**
     * Проверяет формирование имени файла страницы.
     */
    @Test
    public void formatsPageFileName() {
        assertEquals("page_2024-01-01_2024-01-31_00001.json",
                ApiClient.pageFileName(FROM, TO, 1));
        assertEquals("page_2024-01-01_2024-01-31_00123.json",
                ApiClient.pageFileName(FROM, TO, 123));
    }

    /**
     * Проверяет подстановку периода и номера страницы в шаблон URL.
     */
    @Test
    public void buildsPageUriFromTemplate() {
        ApiClient custom = new ApiClient(
                "https://example.test/data?from=%1$s&to=%2$s&page=%3$s",
                "", "", DateTimeFormatter.ofPattern("dd.MM.yyyy"));

        assertEquals("https://example.test/data?from=01.01.2024&to=31.01.2024&page=5",
                custom.buildPageUri(FROM, TO, 5).toString());
    }

    /**
     * Проверяет сохранение страницы в файл.
     *
     * @throws Exception при ошибках ввода-вывода
     */
    @Test
    public void savesPageWithPeriodInName() throws Exception {
        File raw = temporaryFolder.newFolder("raw");

        File saved = client.savePage(raw, FROM, TO, 1, "{\"id\":\"1\"}");

        assertEquals("page_2024-01-01_2024-01-31_00001.json", saved.getName());
        assertEquals("{\"id\":\"1\"}", new String(
                Files.readAllBytes(saved.toPath()), StandardCharsets.UTF_8));
    }
}
