package ru.lakeevda.budgetparticipantsapp.service;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Тесты для {@link ClickHouseTransferService}.
 * Проверяют сборку SQL без подключения к базам данных.
 *
 * @author lakeev-da
 * @since 2026-11-10
 */
public class ClickHouseTransferServiceTest {

    /**
     * Сервис без подключений к БД: проверяется только сборка SQL.
     */
    private final ClickHouseTransferService service =
            new ClickHouseTransferService(null, null);

    /**
     * Проверяет DDL создания таблицы ClickHouse.
     */
    @Test
    public void buildsCreateTableSql() {
        assertEquals("CREATE TABLE denorm_data_staging (`id` String, `name` String)"
                        + " ENGINE = MergeTree ORDER BY id",
                ClickHouseTransferService.buildCreateTableSql(
                        "denorm_data_staging", Arrays.asList("id", "name")));
    }

    /**
     * Проверяет экранирование идентификаторов ClickHouse.
     */
    @Test
    public void quotesIdentifiers() {
        assertEquals("`name`", ClickHouseTransferService.quote("name"));
        assertEquals("`a``b`", ClickHouseTransferService.quote("a`b"));
    }

    /**
     * Проверяет экранирование строковых литералов ClickHouse.
     */
    @Test
    public void escapesStringLiterals() {
        assertEquals("'abc'", ClickHouseTransferService.literal("abc"));
        assertEquals("'a\\'b'", ClickHouseTransferService.literal("a'b"));
        assertEquals("'a\\\\b'", ClickHouseTransferService.literal("a\\b"));
    }

    /**
     * Проверяет запрос чтения из PostgreSQL.
     */
    @Test
    public void buildsSelectSql() {
        assertEquals("SELECT \"id\", \"name\" FROM denorm_data",
                service.buildSelectSql(Arrays.asList("id", "name")));
    }

    /**
     * Проверяет запрос вставки в ClickHouse.
     */
    @Test
    public void buildsInsertSql() {
        assertEquals("INSERT INTO denorm_data (`id`, `name`) VALUES (?, ?)",
                ClickHouseTransferService.buildInsertSql(
                        "denorm_data", Arrays.asList("id", "name")));
        assertEquals("INSERT INTO t (`a`) VALUES (?)",
                ClickHouseTransferService.buildInsertSql(
                        "t", Collections.singletonList("a")));
    }

    /**
     * Проверяет, что перенос включён в конфигурации приложения.
     */
    @Test
    public void readsEnabledFlagFromConfig() {
        assertTrue(service.isEnabled());
    }
}
