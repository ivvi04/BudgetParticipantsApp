package ru.lakeevda.budgetparticipantsapp.service;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Тесты для {@link ClickHouseTransferService}.
 *
 * @author lakeev-da
 * @since 2026-11-06
 */
public class ClickHouseTransferServiceTest {

    /**
     * Проверяет чтение флага включения переноса из конфигурации.
     */
    @Test
    public void readsEnabledFlagFromConfiguration() {
        ClickHouseTransferService service =
                new ClickHouseTransferService(null, "ch_table");
        assertTrue(service.isEnabled());
    }
}
