package ru.lakeevda.budgetparticipantsapp.service;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.StringJoiner;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Сервис для переноса данных из PostgreSQL в ClickHouse.
 * Переносит содержимое денормализованной таблицы.
 */
public class ClickHouseTransferService {
    private static final Logger log = Logger.getLogger(ClickHouseTransferService.class.getName());

    private final String chUrl;
    private final String chUser;
    private final String chPassword;
    private final String chTable;

    public ClickHouseTransferService(Properties cfg) {
        this.chUrl = cfg.getProperty("clickhouse.url", "jdbc:clickhouse://localhost:8123/default");
        this.chUser = cfg.getProperty("clickhouse.user", "default");
        this.chPassword = cfg.getProperty("clickhouse.password", "");
        this.chTable = cfg.getProperty("clickhouse.table", "denorm_data");
    }

    /**
     * Проверяет, включён ли перенос в ClickHouse.
     *
     * @param cfg свойства конфигурации
     * @return true если перенос включён
     */
    public boolean isEnabled(Properties cfg) {
        return Boolean.parseBoolean(cfg.getProperty("clickhouse.enabled", "false"));
    }

    /**
     * Переносит данные из PostgreSQL в ClickHouse.
     *
     * @param pgUrl URL PostgreSQL
     * @param pgUser имя пользователя PostgreSQL
     * @param pgPassword пароль PostgreSQL
     * @param pgTable имя таблицы PostgreSQL
     * @throws SQLException при ошибках работы с БД
     */
    public void transfer(String pgUrl, String pgUser, String pgPassword, String pgTable)
            throws SQLException {
        log.info("Starting transfer from PostgreSQL to ClickHouse");
        try (Connection pg = DriverManager.getConnection(pgUrl, pgUser, pgPassword);
             Connection ch = DriverManager.getConnection(chUrl, chUser, chPassword)) {

            List<String> columns = readPgColumns(pg, pgTable);
            ensureChTable(ch, columns);
            long copied = copyRows(pg, ch, pgTable, columns);
            log.info("ClickHouse: transferred " + copied + " row(s) into " + chTable);
        }
    }

    /**
     * Читает список колонок из PostgreSQL таблицы.
     *
     * @param pg подключение к PostgreSQL
     * @param pgTable имя таблицы
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

