package ru.lakeevda.budgetparticipantsapp.service;

import ru.lakeevda.budgetparticipantsapp.config.ConfigLoader;
import ru.lakeevda.budgetparticipantsapp.db.DatabaseConnection;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Сервис переноса данных из PostgreSQL в ClickHouse.
 *
 * @author lakeev-da
 * @since 2026-11-06
 */
public class ClickHouseTransferService {

    private static final Logger log = Logger.getLogger(ClickHouseTransferService.class.getName());

    private static final String STAGING_SUFFIX = "_staging";
    private static final String BACKUP_SUFFIX = "_backup";

    private final DatabaseConnection clickHouseConnection;
    private final DatabaseConnection postgresConnection;
    private final String table;

    /**
     * Конструктор сервиса переноса в ClickHouse.
     *
     * @param clickHouseConnection подключение к ClickHouse
     * @param postgresConnection подключение к PostgreSQL
     */
    public ClickHouseTransferService(DatabaseConnection clickHouseConnection,
                                     DatabaseConnection postgresConnection) {
        this.clickHouseConnection = clickHouseConnection;
        this.postgresConnection = postgresConnection;
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
     * Переносит данные из PostgreSQL в ClickHouse через промежуточную таблицу.
     *
     * @throws SQLException при ошибках работы с БД
     */
    public void transfer() throws SQLException {
        log.info("Запуск переноса данных из PostgreSQL в ClickHouse");
        String staging = table + STAGING_SUFFIX;
        try (Connection pg = postgresConnection.getConnection();
             Connection ch = clickHouseConnection.getConnection()) {
            pg.setAutoCommit(false);
            long copied;
            try {
                List<String> columns = readPgColumns(pg);
                dropTableIfExists(ch, staging);
                createChTable(ch, staging, columns);
                copied = copyRows(pg, ch, staging, columns);
                replaceTargetTable(ch, staging);
                pg.commit();
            } catch (SQLException e) {
                dropQuietly(ch, staging);
                rollbackQuietly(pg);
                throw e;
            }
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
     * Создаёт таблицу ClickHouse с колонками типа String.
     *
     * @param ch      подключение к ClickHouse
     * @param name    имя создаваемой таблицы
     * @param columns список колонок из PostgreSQL
     * @throws SQLException при ошибках
     */
    private void createChTable(Connection ch, String name, List<String> columns) throws SQLException {
        String ddl = buildCreateTableSql(name, columns);
        try (Statement st = ch.createStatement()) {
            st.execute(ddl);
            log.fine("Таблица ClickHouse создана: " + name);
        }
    }

    /**
     * Строит DDL для таблицы ClickHouse.
     *
     * @param name    имя таблицы
     * @param columns список колонок из PostgreSQL
     * @return SQL-запрос создания таблицы
     */
    static String buildCreateTableSql(String name, List<String> columns) {
        StringBuilder ddl = new StringBuilder("CREATE TABLE " + name + " (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) ddl.append(", ");
            ddl.append(quote(columns.get(i))).append(" String");
        }
        return ddl.append(") ENGINE = MergeTree ORDER BY id").toString();
    }

    /**
     * Подменяет основную таблицу промежуточной.
     *
     * @param ch      подключение к ClickHouse
     * @param staging промежуточная таблица с новыми данными
     * @throws SQLException при ошибках
     */
    private void replaceTargetTable(Connection ch, String staging) throws SQLException {
        if (!tableExists(ch, table)) {
            try (Statement st = ch.createStatement()) {
                st.execute("RENAME TABLE " + staging + " TO " + table);
            }
            log.fine("Таблица ClickHouse " + table + " создана из " + staging);
            return;
        }
        try (Statement st = ch.createStatement()) {
            st.execute("EXCHANGE TABLES " + table + " AND " + staging);
        } catch (SQLException e) {
            log.warning("EXCHANGE TABLES недоступен (" + e.getMessage()
                    + "), используется переименование таблиц");
            replaceByRename(ch, staging);
            return;
        }
        // после обмена в промежуточной таблице лежат прежние данные
        dropTableIfExists(ch, staging);
    }

    /**
     * Подменяет основную таблицу переименованием через резервную.
     * Используется, когда EXCHANGE TABLES недоступен.
     *
     * @param ch      подключение к ClickHouse
     * @param staging промежуточная таблица с новыми данными
     * @throws SQLException при ошибках
     */
    private void replaceByRename(Connection ch, String staging) throws SQLException {
        String backup = table + BACKUP_SUFFIX;
        dropTableIfExists(ch, backup);
        try (Statement st = ch.createStatement()) {
            st.execute("RENAME TABLE " + table + " TO " + backup + ", " + staging + " TO " + table);
        }
        dropTableIfExists(ch, backup);
    }

    /**
     * Проверяет наличие таблицы в текущей базе ClickHouse.
     *
     * @param ch   подключение к ClickHouse
     * @param name имя таблицы
     * @return {@code true}, если таблица существует
     * @throws SQLException при ошибках
     */
    private boolean tableExists(Connection ch, String name) throws SQLException {
        try (Statement st = ch.createStatement();
             ResultSet rs = st.executeQuery("SELECT count() FROM system.tables "
                     + "WHERE database = currentDatabase() AND name = " + literal(name))) {
            rs.next();
            return rs.getLong(1) > 0;
        }
    }

    /**
     * Удаляет таблицу, если она существует.
     *
     * @param ch   подключение к ClickHouse
     * @param name имя таблицы
     * @throws SQLException при ошибках
     */
    private void dropTableIfExists(Connection ch, String name) throws SQLException {
        try (Statement st = ch.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + name);
        }
    }

    /**
     * Удаляет таблицу, не мешая обработке ошибки.
     *
     * @param ch   подключение к ClickHouse
     * @param name имя таблицы
     */
    private void dropQuietly(Connection ch, String name) {
        try {
            dropTableIfExists(ch, name);
        } catch (SQLException e) {
            log.log(Level.WARNING, "Не удалось удалить промежуточную таблицу " + name, e);
        }
    }

    /**
     * Откатывает транзакцию PostgreSQL, не мешая обработке ошибки.
     *
     * @param connection подключение к PostgreSQL
     */
    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            log.log(Level.WARNING, "Не удалось откатить транзакцию PostgreSQL", e);
        }
    }

    /**
     * Копирует строки из PostgreSQL в таблицу ClickHouse.
     *
     * @param pg      подключение к PostgreSQL
     * @param ch      подключение к ClickHouse
     * @param target  имя целевой таблицы ClickHouse
     * @param columns список колонок
     * @return количество скопированных строк
     * @throws SQLException при ошибках
     */
    private long copyRows(Connection pg, Connection ch, String target, List<String> columns)
            throws SQLException {
        String selectSql = buildSelectSql(columns);
        String insertSql = buildInsertSql(target, columns);

        long count = 0;
        try (Statement read = pg.createStatement()) {
            read.setFetchSize(1000);
            try (ResultSet rs = read.executeQuery(selectSql);
                 PreparedStatement ins = ch.prepareStatement(insertSql)) {

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

    /**
     * Строит запрос чтения всех колонок из таблицы PostgreSQL.
     * Идентификаторы заключаются в двойные кавычки.
     *
     * @param columns список колонок
     * @return SQL-запрос выборки
     */
    String buildSelectSql(List<String> columns) {
        StringJoiner names = new StringJoiner(", ");
        for (String col : columns) {
            names.add("\"" + col + "\"");
        }
        return "SELECT " + names + " FROM " + table;
    }

    /**
     * Строит запрос вставки в таблицу ClickHouse.
     * Идентификаторы заключаются в бэктики, значения передаются параметрами.
     *
     * @param target  имя целевой таблицы
     * @param columns список колонок
     * @return SQL-запрос вставки
     */
    static String buildInsertSql(String target, List<String> columns) {
        StringJoiner names = new StringJoiner(", ");
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            names.add(quote(columns.get(i)));
            placeholders.append(i == 0 ? "?" : ", ?");
        }
        return "INSERT INTO " + target + " (" + names + ") VALUES (" + placeholders + ")";
    }

    /**
     * Заключает идентификатор ClickHouse в бэктики.
     *
     * @param name имя идентификатора
     * @return экранированный идентификатор
     */
    static String quote(String name) {
        return '`' + name.replace("`", "``") + '`';
    }

    /**
     * Заключает строковое значение в одинарные кавычки для ClickHouse.
     *
     * @param value значение
     * @return экранированный литерал
     */
    static String literal(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
