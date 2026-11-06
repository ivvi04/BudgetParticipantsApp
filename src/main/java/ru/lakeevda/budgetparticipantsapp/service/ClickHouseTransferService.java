package ru.lakeevda.budgetparticipantsapp.service;

import ru.lakeevda.budgetparticipantsapp.config.ConfigLoader;
import ru.lakeevda.budgetparticipantsapp.db.DatabaseConnection;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
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

    private final DatabaseConnection clickHouseConnection;
    private final String chTable;

    /**
     * Конструктор сервиса переноса в ClickHouse.
     *
     * @param clickHouseConnection подключение к ClickHouse
     * @param table имя таблицы в ClickHouse
     */
    public ClickHouseTransferService(DatabaseConnection clickHouseConnection, String table) {
        this.clickHouseConnection = clickHouseConnection;
        this.chTable = table;
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
     * @param postgresConnection подключение к PostgreSQL
     * @param postgresTable имя таблицы PostgreSQL
     * @throws SQLException при ошибках работы с БД
     */
    public void transfer(DatabaseConnection postgresConnection, String postgresTable)
            throws SQLException {
        log.info("Starting transfer from PostgreSQL to ClickHouse");
        try (Connection pg = postgresConnection.getConnection();
             Connection ch = clickHouseConnection.getConnection()) {

            List<String> columns = readPgColumns(pg, postgresTable);
            ensureChTable(ch, columns);
            long copied = copyRows(pg, ch, postgresTable, columns);
            log.info("ClickHouse: transferred " + copied + " row(s) into " + chTable);
        }
    }

    /**
     * Читает список колонок из PostgreSQL таблицы.
     *
     * @param pg подключение к PostgreSQL
     * @param table имя таблицы
     * @return список имён колонок
     * @throws SQLException при ошибках
     */
    private List<String> readPgColumns(Connection pg, String table) throws SQLException {
        List<String> cols = new ArrayList<>();
        try (ResultSet rs = pg.getMetaData().getColumns(null, null, table.toLowerCase(), null)) {
            while (rs.next()) cols.add(rs.getString("COLUMN_NAME"));
        }
        if (cols.isEmpty()) throw new SQLException("No columns found in PostgreSQL table " + table);
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
        StringBuilder ddl = new StringBuilder("CREATE TABLE IF NOT EXISTS " + chTable + " (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) ddl.append(", ");
            // Все значения денормализованной таблицы text -> String в ClickHouse
            ddl.append('`').append(columns.get(i)).append("` String");
        }
        ddl.append(") ENGINE = MergeTree ORDER BY id");
        try (Statement st = ch.createStatement()) {
            st.execute(ddl.toString());
            log.fine("ClickHouse table ensured: " + chTable);
        }
    }

    /**
     * Копирует строки из PostgreSQL в ClickHouse.
     *
     * @param pg подключение к PostgreSQL
     * @param ch подключение к ClickHouse
     * @param pgTable имя таблицы в PostgreSQL
     * @param columns список колонок
     * @return количество скопированных строк
     * @throws SQLException при ошибках
     */
    private long copyRows(Connection pg, Connection ch, String pgTable,
                          List<String> columns) throws SQLException {
        StringJoiner names = new StringJoiner(", ");
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            names.add('`' + columns.get(i) + '`');
            placeholders.append(i == 0 ? "?" : ", ?");
        }

        long count = 0;
        try (Statement read = pg.createStatement()) {
            read.setFetchSize(1000);
            try (ResultSet rs = read.executeQuery("SELECT " + names + " FROM " + pgTable);
                 PreparedStatement ins = ch.prepareStatement(
                         "INSERT INTO " + chTable + " (" + names + ") VALUES ("
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

