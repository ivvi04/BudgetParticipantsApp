package ru.lakeevda.budgetparticipantsapp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * Тесты для {@link ApiClient}.
 * Проверяют извлечение массива записей из ответа API.
 *
 * @author lakeev-da
 * @since 2026-11-06
 */
public class ApiClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ApiClient client;

    /** Создаёт клиента с конфигурацией из classpath. */
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
}
