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
 * Создаёт таблицу, добавляет недостающие колонки динамически и загружает данные.
 *
 * @author lakeev-da
 * @since 2026-11-06
 */
public class DataLoadService {

    private static final Logger log = Logger.getLogger(DataLoadService.class.getName());

    private static final int COLUMN_LIMIT_SOFT = 1500;
    private static final int COLUMN_LIMIT_HARD = 1595;
    private static final int DEFAULT_BATCH_SIZE = 32000;

    /** Колонка первичного ключа таблицы. */
    private static final String ID_COLUMN = "id";

    /** Разделитель колонок в сигнатуре группы строк. */
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
     * Удаляет предыдущие данные за период и перезаписывает новыми.
     * Если запись с таким же {@value #ID_COLUMN} уже есть (например, загружена
     * за другой период), она обновляется значениями из новой выгрузки.
     *
     * @param pageFiles файлы с JSON данными
     * @param from начальная дата периода
     * @param to конечная дата периода
     * @throws SQLException при ошибках работы с БД
     */
    public void load(List<File> pageFiles, LocalDate from, LocalDate to) throws SQLException {
        log.info(String.format("Запуск загрузки данных за период %s..%s, файлов: %d", from, to, pageFiles.size()));
        
        try (Connection connection = databaseConnection.getConnection()) {
            connection.setAutoCommit(false);

            ensureTable(connection);

            // Собираем все колонки, которые встречаются в скачанных данных
            Set<String> allCols = new TreeSet<>();
            List<Map<String, String>> rows = new ArrayList<>();
            for (File f : pageFiles) {
                try {
                    JsonNode root = mapper.readTree(f);
                    // Массив записей лежит в поле "data" корневого объекта;
                    // если его нет — считаем, что корень сам является массивом
                    JsonNode items = root.has("data") ? root.get("data") : root;
                    if (!items.isArray()) {
                        throw new SQLException("Неожиданная структура JSON в " + f
                                + ": поле 'data' отсутствует, а корень не является массивом");
                    }
                    for (JsonNode item : items) {
                        Map<String, String> flat = JsonFlattener.flatten(item);
                        flat.put("last_update_from", from.toString());
                        flat.put("last_update_to", to.toString());
                        allCols.addAll(flat.keySet());
                        rows.add(flat);
                    }
                } catch (SQLException e) {
                    throw e;
                } catch (Exception e) {
                    log.log(Level.INFO, "Не удалось разобрать " + f, e);
                    throw new SQLException("Не удалось разобрать " + f, e);
                }
            }

            Set<String> tableCols = addMissingColumns(connection, allCols);
            deleteByPeriod(connection, from, to);
            insertRows(connection, rows, tableCols);

            connection.commit();
            log.info("Загрузка данных успешно завершена");
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
     * Добавляет недостающие колонки в таблицу.
     *
     * @param conn подключение к БД
     * @param cols набор новых колонок
     * @return фактический набор колонок таблицы
     * @throws SQLException при ошибках работы с БД
     */
    private Set<String> addMissingColumns(Connection conn, Set<String> cols) throws SQLException {
        Set<String> existing = new HashSet<>();
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, table.toLowerCase(), null)) {
            while (rs.next()) existing.add(rs.getString("COLUMN_NAME"));
        }

        int before = existing.size();
        int planned = before + cols.size();

        if (planned >= COLUMN_LIMIT_SOFT) {
            log.warning("Таблица '" + table + "' приближается к лимиту колонок PostgreSQL: "
                    + "сейчас " + before + " колонок, "
                    + planned + " после этой загрузки (мягкий лимит " + COLUMN_LIMIT_SOFT
                    + ", жёсткий лимит 1600).");
        }

        int skipped = 0;
        for (String col : cols) {
            if (existing.contains(col)) continue;
            if (existing.size() >= COLUMN_LIMIT_HARD) {
                skipped++;
                continue;
            }
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + col + " text");
                existing.add(col);
                log.fine("Добавлена колонка: " + col);
            }
        }
        if (skipped > 0) {
            log.warning("Колонок не добавлено: " + skipped + " (жёсткий лимит "
                    + COLUMN_LIMIT_HARD + "). Их значения будут пропущены.");
        }
        return existing; // фактический набор колонок таблицы
    }

    /**
     * Удаляет строки за указанный период.
     *
     * @param conn подключение к базе данных
     * @param from начальная дата периода
     * @param to конечная дата периода
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

        log.info("Вставка строк: колонок в таблице=" + colList.size()
                + ", групп строк=" + groups.size());

        for (RowGroup group : groups.values()) {
            insertGroup(conn, group);
        }
    }

    /**
     * Группирует строки по одинаковой сигнатуре непустых колонок.
     * Порядок групп соответствует порядку первого появления сигнатуры.
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
    private static List<String> presentColumns(Map<String, String> row, List<String> colList) {
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
     * Конфликты по первичному ключу разрешаются обновлением существующей строки.
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
     * При совпадении первичного ключа ({@value #ID_COLUMN}) запись обновляется
     * значениями из новой загрузки: строка могла быть загружена ранее за другой период.
     *
     * @param columns колонки группы
     * @return SQL-запрос вставки
     */
    private String buildInsertSql(List<String> columns) {
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
     * Обновляются только колонки, присутствующие в данной группе строк,
     * поэтому колонки, отсутствующие в новой записи, сохраняют прежние значения.
     *
     * @param columns колонки группы
     * @return конструкция {@code ON CONFLICT ...} или пустая строка,
     *         если первичного ключа нет в наборе колонок
     */
    private static String buildConflictClause(List<String> columns) {
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

