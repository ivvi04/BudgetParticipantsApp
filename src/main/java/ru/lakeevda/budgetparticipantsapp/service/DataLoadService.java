package ru.lakeevda.budgetparticipantsapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.lakeevda.budgetparticipantsapp.config.ConfigLoader;
import ru.lakeevda.budgetparticipantsapp.db.PostgresConnection;
import ru.lakeevda.budgetparticipantsapp.mapper.JsonFlattener;

import java.io.File;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Сервис для загрузки данных в базу данных.
 *
 * @author lakeev-da
 * @since 2026-11-06
 */
public class DataLoadService {

    private static final Logger log = Logger.getLogger(DataLoadService.class.getName());

    private static final int COLUMN_LIMIT_SOFT = 1500;
    private static final int COLUMN_LIMIT_HARD = 1595;
    private static final int DEFAULT_BATCH_SIZE = 32000;
    private static final String ID_COLUMN = "id";
    private static final String SIGNATURE_SEPARATOR = "\u0001";

    private final PostgresConnection databaseConnection;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String table;

    /**
     * Конструктор сервиса загрузки данных.
     *
     * @param databaseConnection подключение к базе данных
     */
    public DataLoadService(PostgresConnection databaseConnection) {
        this.databaseConnection = databaseConnection;
        this.table = ConfigLoader.getProperty("db.table");
    }

    /**
     * Загружает данные из файлов JSON в базу данных.
     *
     * @param pageFiles файлы с JSON данными
     * @param from      начальная дата периода
     * @param to        конечная дата периода
     * @throws SQLException при ошибках работы с БД
     */
    public void load(List<File> pageFiles, LocalDate from, LocalDate to) throws SQLException {
        log.info(String.format("Запуск загрузки данных за период %s..%s, файлов: %d", from, to, pageFiles.size()));

        try (Connection connection = databaseConnection.getConnection()) {
            connection.setAutoCommit(false);

            ensureTable(connection);
            Set<String> tableCols = readTableColumns(connection);
            deleteByPeriod(connection, from, to);
            connection.commit();

            long inserted = 0;
            for (File f : pageFiles) {
                List<Map<String, String>> rows = readRows(f, from, to);
                if (rows.isEmpty()) {
                    log.fine("Нет записей в " + f.getName());
                    continue;
                }
                Set<String> required = new HashSet<>(tableCols);
                for (Map<String, String> row : rows) {
                    required.addAll(row.keySet());
                }
                tableCols = addMissingColumns(connection, tableCols, required);
                List<Map<String, String>> fitting = filterOverflowRows(rows, tableCols);
                insertRows(connection, fitting, tableCols);
                connection.commit();
                inserted += fitting.size();
            }

            log.info("Загрузка данных успешно завершена, вставлено строк: " + inserted);
        }
    }

    /**
     * Читает записи одной страницы выгрузки.
     *
     * @param file файл страницы
     * @param from начальная дата периода
     * @param to   конечная дата периода
     * @return плоские строки страницы с проставленным периодом
     * @throws SQLException если файл не удаётся разобрать
     */
    List<Map<String, String>> readRows(File file, LocalDate from, LocalDate to)
            throws SQLException {
        try {
            JsonNode root = mapper.readTree(file);
            JsonNode items = root.has("data") ? root.get("data") : root;
            if (!items.isArray()) {
                throw new SQLException("Неожиданная структура JSON в " + file
                        + ": поле 'data' отсутствует, а корень не является массивом");
            }
            List<Map<String, String>> rows = new ArrayList<>(items.size());
            for (JsonNode item : items) {
                Map<String, String> flat = JsonFlattener.flatten(item);
                flat.put("last_update_from", from.toString());
                flat.put("last_update_to", to.toString());
                rows.add(flat);
            }
            return rows;
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            log.log(Level.INFO, "Не удалось разобрать " + file, e);
            throw new SQLException("Не удалось разобрать " + file, e);
        }
    }

    /**
     * Создаёт таблицу, если она не существует.
     *
     * @param conn подключение к базе данных
     * @throws SQLException если таблица не может быть создана
     */
    private void ensureTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " ("
                    + ID_COLUMN + " text primary key, "
                    + "last_update_from text NOT NULL, "
                    + "last_update_to text NOT NULL)");
            log.info("Таблица '" + table + "' готова");
        }
    }

    /**
     * Читает фактический набор колонок таблицы.
     *
     * @param conn подключение к БД
     * @return набор имён колонок, имеющихся в таблице
     * @throws SQLException при ошибках работы с БД
     */
    private Set<String> readTableColumns(Connection conn) throws SQLException {
        Set<String> existing = new HashSet<>();
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, table.toLowerCase(), null)) {
            while (rs.next()) existing.add(rs.getString("COLUMN_NAME"));
        }
        return existing;
    }

    /**
     * Добавляет недостающие колонки в таблицу.
     *
     * @param conn     подключение к БД
     * @param existing колонки, уже имеющиеся в таблице
     * @param cols     набор требуемых колонок
     * @return фактический набор колонок таблицы
     * @throws SQLException при ошибках работы с БД
     */
    private Set<String> addMissingColumns(Connection conn, Set<String> existing, Set<String> cols)
            throws SQLException {
        Set<String> missing = new TreeSet<>();
        for (String col : cols) {
            if (!existing.contains(col)) missing.add(col);
        }
        if (missing.isEmpty()) {
            return existing;
        }

        int before = existing.size();
        int planned = before + missing.size();
        if (before < COLUMN_LIMIT_SOFT && planned >= COLUMN_LIMIT_SOFT) {
            log.warning("Таблица '" + table + "' приближается к лимиту колонок PostgreSQL: "
                    + "сейчас " + before + " колонок, "
                    + planned + " после этой загрузки (мягкий лимит " + COLUMN_LIMIT_SOFT
                    + ", жёсткий лимит 1600).");
        }

        for (String col : missing) {
            if (existing.size() >= COLUMN_LIMIT_HARD) {
                continue;
            }
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + col + " text");
                existing.add(col);
                log.fine("Добавлена колонка: " + col);
            }
        }
        return existing;
    }

    /**
     * Отбрасывает записи, для которых в таблице не хватает колонок.
     *
     * @param rows      строки страницы
     * @param tableCols фактический набор колонок таблицы
     * @return строки, которые можно вставить без потери значений
     */
    static List<Map<String, String>> filterOverflowRows(List<Map<String, String>> rows,
                                                        Set<String> tableCols) {
        List<Map<String, String>> fitting = new ArrayList<>(rows.size());
        for (Map<String, String> row : rows) {
            int missing = countMissingColumns(row, tableCols);
            if (missing == 0) {
                fitting.add(row);
                continue;
            }
            String id = row.get(ID_COLUMN);
            log.warning("Запись с id " + (id == null || id.isEmpty() ? "<без id>" : id)
                    + " пропущена по причине превышения количества колонок: требуется "
                    + (tableCols.size() + missing) + " из возможных " + COLUMN_LIMIT_HARD
                    + " (не поместилось " + missing + ").");
        }
        return fitting;
    }

    /**
     * Считает непустые значения строки, для которых в таблице нет колонки.
     *
     * @param row       строка данных
     * @param tableCols фактический набор колонок таблицы
     * @return число значений, для которых не хватило колонок
     */
    private static int countMissingColumns(Map<String, String> row, Set<String> tableCols) {
        int missing = 0;
        for (Map.Entry<String, String> entry : row.entrySet()) {
            if (isMissingValue(entry, tableCols)) {
                missing++;
            }
        }
        return missing;
    }

    /**
     * Проверяет, что значение колонки непустое и самой колонки нет в таблице.
     *
     * @param entry     пара «колонка → значение»
     * @param tableCols фактический набор колонок таблицы
     * @return {@code true}, если для значения не хватает колонки
     */
    private static boolean isMissingValue(Map.Entry<String, String> entry, Set<String> tableCols) {
        String value = entry.getValue();
        return value != null && !value.isEmpty() && !tableCols.contains(entry.getKey());
    }

    /**
     * Удаляет строки за указанный период.
     *
     * @param conn подключение к базе данных
     * @param from начальная дата периода
     * @param to   конечная дата периода
     * @throws SQLException при ошибках работы с БД
     */
    private void deleteByPeriod(Connection conn, LocalDate from, LocalDate to) throws SQLException {
        String sql = "DELETE FROM " + table
                + " WHERE last_update_from = ? AND last_update_to = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, from.toString());
            ps.setString(2, to.toString());
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                log.info("Перезапись: удалено ранее загруженных строк за тот же период: " + deleted);
            } else {
                log.fine("За период " + from + ".." + to + " строки для удаления не найдены");
            }
        }
    }

    /**
     * Вставляет строки в таблицу с группировкой по сигнатуре колонок.
     *
     * @param conn подключение к БД
     * @param rows список строк для вставки
     * @param cols набор всех колонок таблицы
     * @throws SQLException при ошибках работы с БД
     */
    private void insertRows(Connection conn, List<Map<String, String>> rows,
                            Set<String> cols) throws SQLException {
        if (rows.isEmpty()) {
            log.fine("Нет строк для вставки");
            return;
        }
        List<String> colList = new ArrayList<>(new TreeSet<>(cols));
        Map<String, RowGroup> groups = groupRows(rows, colList);

        log.fine("Вставка строк: колонок в таблице=" + colList.size()
                + ", групп строк=" + groups.size());

        for (RowGroup group : groups.values()) {
            insertGroup(conn, group);
        }
    }

    /**
     * Группирует строки по сигнатуре непустых колонок.
     *
     * @param rows    строки для вставки
     * @param colList отсортированный список всех колонок таблицы
     * @return группы строк по сигнатуре колонок
     */
    private Map<String, RowGroup> groupRows(List<Map<String, String>> rows,
                                            List<String> colList) {
        Map<String, RowGroup> groups = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            List<String> present = presentColumns(row, colList);
            String key = String.join(SIGNATURE_SEPARATOR, present);
            RowGroup group = groups.get(key);
            if (group == null) {
                group = new RowGroup(present);
                groups.put(key, group);
            }
            group.rows.add(row);
        }
        return groups;
    }

    /**
     * Определяет колонки, значения которых непустые в данной строке.
     *
     * @param row     строка данных
     * @param colList отсортированный список всех колонок таблицы
     * @return список непустых колонок в порядке {@code colList}
     */
    static List<String> presentColumns(Map<String, String> row, List<String> colList) {
        List<String> present = new ArrayList<>();
        for (String col : colList) {
            String value = row.get(col);
            if (value != null && !value.isEmpty()) {
                present.add(col);
            }
        }
        return present;
    }

    /**
     * Вставляет строки одной группы с разбиением на батчи.
     *
     * @param conn  подключение к БД
     * @param group группа строк с одинаковой сигнатурой колонок
     * @throws SQLException при ошибках работы с БД
     */
    private void insertGroup(Connection conn, RowGroup group) throws SQLException {
        String sql = buildInsertSql(group.columns);
        int batchSize = Math.max(1, DEFAULT_BATCH_SIZE / group.columns.size());

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int inBatch = 0;
            for (Map<String, String> row : group.rows) {
                bindRow(ps, group.columns, row);
                ps.addBatch();
                if (++inBatch >= batchSize) {
                    ps.executeBatch();
                    inBatch = 0;
                }
            }
            flushBatch(ps, inBatch);
        }
    }

    /**
     * Строит SQL-запрос вставки для набора колонок.
     *
     * @param columns колонки группы
     * @return SQL-запрос вставки
     */
    String buildInsertSql(List<String> columns) {
        StringJoiner names = new StringJoiner(", ");
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            names.add(columns.get(i));
            placeholders.append(i == 0 ? "?" : ", ?");
        }
        return "INSERT INTO " + table + " (" + names + ") VALUES ("
                + placeholders + ")" + buildConflictClause(columns);
    }

    /**
     * Строит завершающую часть запроса вставки, разрешающую конфликт по первичному ключу.
     *
     * @param columns колонки группы
     * @return конструкция {@code ON CONFLICT ...} или пустая строка,
     * если первичного ключа нет в наборе колонок
     */
    static String buildConflictClause(List<String> columns) {
        if (!columns.contains(ID_COLUMN)) {
            // без ключа конфликтовать по первичному ключу невозможно
            return "";
        }
        StringJoiner updates = new StringJoiner(", ");
        for (String col : columns) {
            if (ID_COLUMN.equals(col)) continue;
            updates.add(col + " = EXCLUDED." + col);
        }
        if (updates.length() == 0) {
            // в группе только ключ: обновлять нечего
            return " ON CONFLICT (" + ID_COLUMN + ") DO NOTHING";
        }
        return " ON CONFLICT (" + ID_COLUMN + ") DO UPDATE SET " + updates;
    }

    /**
     * Привязывает значения строки к параметрам запроса.
     *
     * @param ps      подготовленный запрос
     * @param columns колонки группы
     * @param row     строка данных
     * @throws SQLException при ошибке привязки параметров
     */
    private static void bindRow(PreparedStatement ps, List<String> columns,
                                Map<String, String> row) throws SQLException {
        for (int i = 0; i < columns.size(); i++) {
            // каждый параметр гарантированно имеет значение — NULL нет
            ps.setString(i + 1, row.get(columns.get(i)));
        }
    }

    /**
     * Выполняет накопленный батч, если в нём остались строки.
     *
     * @param ps      подготовленный запрос
     * @param inBatch число строк в незавершённом батче
     * @throws SQLException при ошибках работы с БД
     */
    private void flushBatch(PreparedStatement ps, int inBatch) throws SQLException {
        if (inBatch > 0) {
            ps.executeBatch();
            log.fine("Выполнен батч из строк: " + inBatch);
        }
    }

    /**
     * Группа строк с одинаковой сигнатурой непустых колонок.
     */
    private static final class RowGroup {

        private final List<String> columns;
        private final List<Map<String, String>> rows = new ArrayList<>();

        private RowGroup(List<String> columns) {
            this.columns = columns;
        }
    }
}

