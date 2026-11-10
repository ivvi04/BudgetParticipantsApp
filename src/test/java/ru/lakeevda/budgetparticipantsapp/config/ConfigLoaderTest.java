package ru.lakeevda.budgetparticipantsapp.config;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Тесты для {@link ConfigLoader}.
 * Используют application.properties из classpath.
 *
 * @author lakeev-da
 * @since 2026-11-10
 */
public class ConfigLoaderTest {

    /**
     * Проверяет чтение существующего свойства.
     */
    @Test
    public void readsExistingProperty() {
        assertEquals("denorm_data", ConfigLoader.getProperty("db.table"));
    }

    /**
     * Проверяет значение по умолчанию для отсутствующего свойства.
     */
    @Test
    public void returnsDefaultForMissingProperty() {
        assertEquals("fallback", ConfigLoader.getProperty("no.such.key", "fallback"));
    }

    /**
     * Проверяет ошибку для отсутствующего свойства без значения по умолчанию.
     */
    @Test(expected = RuntimeException.class)
    public void throwsForMissingProperty() {
        ConfigLoader.getProperty("no.such.key");
    }
}
