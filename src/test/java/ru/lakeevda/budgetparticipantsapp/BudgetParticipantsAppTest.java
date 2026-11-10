package ru.lakeevda.budgetparticipantsapp;

import org.junit.Test;

import java.time.LocalDate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Тесты для {@link BudgetParticipantsApp}.
 * Проверяют валидацию периода без запуска приложения.
 *
 * @author lakeev-da
 * @since 2026-11-10
 */
public class BudgetParticipantsAppTest {

    /**
     * Проверяет, что корректный период принимается.
     */
    @Test
    public void acceptsValidPeriod() {
        BudgetParticipantsApp.validatePeriod(
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31));
        BudgetParticipantsApp.validatePeriod(
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 1));
    }

    /**
     * Проверяет, что обратный период отклоняется.
     */
    @Test
    public void rejectsReversedPeriod() {
        try {
            BudgetParticipantsApp.validatePeriod(
                    LocalDate.of(2024, 2, 1), LocalDate.of(2024, 1, 31));
            fail("Ожидалось исключение для обратного периода");
        } catch (IllegalArgumentException e) {
            assertEquals("Дата lastUpdateFrom не может быть позже lastUpdateTo",
                    e.getMessage());
        }
    }
}
