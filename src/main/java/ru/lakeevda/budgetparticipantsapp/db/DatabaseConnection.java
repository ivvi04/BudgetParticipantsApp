package ru.lakeevda.budgetparticipantsapp.db;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Интерфейс для подключения к базе данных.
 * Обеспечивает абстракцию над различными типами БД (PostgreSQL, ClickHouse).
 *
 * @author lakeev-da
 * @since 2026-11-06
 */
public interface DatabaseConnection {
    /**
     * Возвращает имя базы данных.
     *
     * @return имя базы данных
     */
    String getDatabaseName();

    /**
     * Возвращает подключение к базе данных.
     *
     * @return подключение
     * @throws SQLException при ошибках подключения
     */
    Connection getConnection() throws SQLException;
}
