package ru.lakeevda.budgetparticipantsapp.service;

import ru.lakeevda.budgetparticipantsapp.config.ConfigLoader;
import ru.lakeevda.budgetparticipantsapp.db.DatabaseConnection;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.logging.Logger;

/**
 * Сервис для переноса данных из PostgreSQL в ClickHouse.
 * Переносит содержимое денормализованной таблицы.
 *
 * @author lakeev-da
 * @since 2026-11-06
 */
public class ClickHouseTransferService {

    private static final Logger log = Logger.getLogger(ClickHouseTransferService.class.getName());

    private final DatabaseConnection chConnection;
    private final DatabaseConnection pgConnection;
    private final String table;

    /**
     * Конструктор сервиса переноса в ClickHouse.
     *
     * @param chConnection подключение к ClickHouse
     */
    public ClickHouseTransferService(DatabaseConnection chConnection,
                                     DatabaseConnection pgConnection) {
        this.chConnection = chConnection;
        this.pgConnection = pgConnection;
        this.table = ConfigLoader.getProperty("db.table");
    }

    /**
     * Проверяет, включён ли перенос в ClickHouse.
     *
     * @return true если перенос включён, false иначе
     */
    public boolean isEnabled() {
        return Boolean.parseBoolean(ConfigLoader.getProperty("clickhouse.enabled", "false"));
    }

    /**
     * Переносит данные из PostgreSQL в ClickHouse.
     *
     * @throws SQLException при ошибках работы с БД
     */
    public void transfer()
            throws SQLException {
        log.info("Запуск переноса данных из PostgreSQL в ClickHouse");
        try (Connection pg = pgConnection.getConnection();
             Connection ch = chConnection.getConnection()) {

            List<String> columns = readPgColumns(pg);
            ensureChTable(ch, columns);
            long copied = copyRows(pg, ch, columns);
            log.info("ClickHouse: перенесено строк: " + copied + " в таблицу " + table);
        }
    }

    /**
     * Читает список колонок из PostgreSQL таблицы.
     *
     * @param pg подключение к PostgreSQL
     * @return список имён колонок
     * @throws SQLException при ошибках
     */
    private List<String> readPgColumns(Connection pg) throws SQLException {
        List<String> cols = new ArrayList<>();
        try (ResultSet rs = pg.getMetaData().getColumns(null, null, table.toLowerCase(), null)) {
            while (rs.next()) cols.add(rs.getString("COLUMN_NAME"));
        }
        if (cols.isEmpty()) throw new SQLException("В таблице PostgreSQL " + table + " не найдено колонок");
        return cols;
    }

    /**
     * Создаёт таблицу в ClickHouse, если она не существует.
     *
     * @param ch подключение к ClickHouse
     * @param columns список колонок из PostgreSQL
     * @throws SQLException при ошибках
     */
    private void ensureChTable(Connection ch, List<String> columns) throws SQLException {
        StringBuilder ddl = new StringBuilder("CREATE TABLE IF NOT EXISTS " + table + " (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) ddl.append(", ");
            // Все значения денормализованной таблицы text -> String в ClickHouse
            ddl.append('`').append(columns.get(i)).append("` String");
        }
        ddl.append(") ENGINE = MergeTree ORDER BY id");
        try (Statement st = ch.createStatement()) {
            st.execute(ddl.toString());
            log.fine("Таблица ClickHouse готова: " + table);
        }
    }

    /**
     * Копирует строки из PostgreSQL в ClickHouse.
     *
     * @param pg подключение к PostgreSQL
     * @param ch подключение к ClickHouse
     * @param columns список колонок
     * @return количество скопированных строк
     * @throws SQLException при ошибках
     */
    private long copyRows(Connection pg, Connection ch, List<String> columns) throws SQLException {
        // PostgreSQL: идентификаторы в двойных кавычках
        StringJoiner pgNames = new StringJoiner(", ");
        for (String col : columns) {
            pgNames.add("\"" + col + "\"");
        }
        // ClickHouse: идентификаторы в бэктиках (или без кавычек вовсе)
        StringJoiner chNames = new StringJoiner(", ");
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            chNames.add('`' + columns.get(i) + '`');
            placeholders.append(i == 0 ? "?" : ", ?");
        }

        long count = 0;
        try (Statement read = pg.createStatement()) {
            read.setFetchSize(1000);
            try (ResultSet rs = read.executeQuery("SELECT " + pgNames + " FROM " + table);
                 PreparedStatement ins = ch.prepareStatement(
                         "INSERT INTO " + table + " (" + chNames + ") VALUES ("
                                 + placeholders + ")")) {

                // Пустые значения денормализованной таблицы пишем пустой строкой:
                // обе БД ведут себя одинаково без Nullable-обёрток
                int batch = 0;
                while (rs.next()) {
                    for (int i = 1; i <= columns.size(); i++) {
                        String v = rs.getString(i);
                        ins.setString(i, v == null ? "" : v);
                    }
                    ins.addBatch();
                    if (++batch % 5000 == 0) ins.executeBatch();
                }
                ins.executeBatch();
                count = batch;
            }
        }
        return count;
    }
}

