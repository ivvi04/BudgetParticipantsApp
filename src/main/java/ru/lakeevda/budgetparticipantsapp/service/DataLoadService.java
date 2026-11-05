package ru.lakeevda.budgetparticipantsapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.lakeevda.budgetparticipantsapp.db.DatabaseConnection;
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
 */
public class DataLoadService {
    private static final Logger log = Logger.getLogger(DataLoadService.class.getName());

    // Жёсткий предел Postgres - 1600 колонок. При подходе к 1500 - предупреждаем,
    // после 1595 - новый столбец не создаём, чтобы не уронить ALTER TABLE.
    private static final int COLUMN_LIMIT_SOFT = 1500;
    private static final int COLUMN_LIMIT_HARD = 1595;

    private static final int DEFAULT_BATCH_SIZE = 32000;

    private final DatabaseConnection databaseConnection;
    private final ObjectMapper mapper = new ObjectMapper();

    private final String table;

    public DataLoadService(DatabaseConnection databaseConnection, String table) {
        this.databaseConnection = databaseConnection;
        this.table = table;
    }

    /**
     * Загружает данные из файлов JSON в базу данных.
     *
     * @param pageFiles файлы с JSON данными
     * @param from начальная дата периода
     * @param to конечная дата периода
     * @throws SQLException при ошибках работы с БД
     */
    public void load(List<File> pageFiles, LocalDate from, LocalDate to) throws SQLException {
        log.info(String.format("Starting data load for period %s..%s, %d files", from, to, pageFiles.size()));
        
        try (Connection conn = databaseConnection.getConnection()) {
            conn.setAutoCommit(false);

            ensureTable(conn);

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
                        throw new SQLException("Unexpected JSON structure in " + f
                                + ": field 'data' is missing and root is not an array");
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
                    log.log(Level.SEVERE, "Cannot parse " + f, e);
                    throw new SQLException("Cannot parse " + f, e);
                }
            }

            Set<String> tableCols = addMissingColumns(conn, allCols);
            deleteByPeriod(conn, from, to);
            insertRows(conn, rows, tableCols);

            conn.commit();
            log.info("Data load completed successfully");
        }
    }

    /**
     * Создаёт таблицу, если она не существует.
     */
    private void ensureTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " ("
                    + "id text primary key, "
                    + "last_update_from text NOT NULL, "
                    + "last_update_to text NOT NULL)");
            log.info("Table '" + table + "' ensured");
        }
    }

    /**
     * Добавляет недостающие колонки в таблицу.
     *
     * @param conn подключение к БД
     * @param cols набор новых колонок
     * @return фактический набор колонок таблицы
     */
    private Set<String> addMissingColumns(Connection conn, Set<String> cols) throws SQLException {
        Set<String> existing = new HashSet<>();
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, table.toLowerCase(), null)) {
            while (rs.next()) existing.add(rs.getString("COLUMN_NAME"));
        }

        int before = existing.size();
        int planned = before + cols.size();

        if (planned >= COLUMN_LIMIT_SOFT) {
            log.warning("Table '" + table + "' is approaching the PostgreSQL "
                    + "column limit: currently " + before + " columns, "
                    + planned + " after this load (soft limit " + COLUMN_LIMIT_SOFT
                    + ", hard limit 1600).");
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
                log.fine("Added column: " + col);
            }
        }
        if (skipped > 0) {
            log.warning("" + skipped + " column(s) NOT added (hard limit "
                    + COLUMN_LIMIT_HARD + "). Their values will be skipped.");
        }
        return existing; // фактический набор колонок таблицы
    }

    /**
     * Удаляет строки за указанный период.
     */
    private void deleteByPeriod(Connection conn, LocalDate from, LocalDate to) throws SQLException {
        String sql = "DELETE FROM " + table
                + " WHERE last_update_from = ? AND last_update_to = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, from.toString());
            ps.setString(2, to.toString());
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                log.info("Overwriting: deleted " + deleted
                        + " previously loaded row(s) for the same period");
            } else {
                log.fine("No rows deleted for period " + from + ".." + to);
            }
        }
    }

    /**
     * Вставляет строки в таблицу с группировкой по сигнатуре колонок.
     *
     * @param conn подключение к БД
     * @param rows список строк для вставки
     * @param cols набор всех колонок таблицы
     */
    private void insertRows(Connection conn, List<Map<String, String>> rows,
                            Set<String> cols) throws SQLException {
        if (rows.isEmpty()) {
            log.fine("No rows to insert");
            return;
        }
        List<String> colList = new ArrayList<>(new TreeSet<>(cols));

        // Группируем строки по одинаковой сигнатуре непустых колонок
        Map<String, List<Map<String, String>>> groups = new LinkedHashMap<>();
        Map<String, List<String>> groupCols = new HashMap<>();
        for (Map<String, String> row : rows) {
            List<String> present = new ArrayList<>();
            for (String c : colList) {
                String v = row.get(c);
                if (v != null && !v.isEmpty()) present.add(c);
            }
            String key = String.join("\u0001", present);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            groupCols.putIfAbsent(key, present);
        }

        log.info("insertRows: tableCols=" + colList.size()
                + ", rowGroups=" + groups.size());

        for (Map.Entry<String, List<Map<String, String>>> g : groups.entrySet()) {
            List<String> groupColList = groupCols.get(g.getKey());
            int batchSize = Math.max(1, DEFAULT_BATCH_SIZE / groupColList.size());
            StringJoiner names = new StringJoiner(", ");
            StringBuilder placeholders = new StringBuilder();
            for (int i = 0; i < groupColList.size(); i++) {
                names.add(groupColList.get(i));
                placeholders.append(i == 0 ? "?" : ", ?");
            }
            String sql = "INSERT INTO " + table + " (" + names + ") VALUES ("
                    + placeholders + ")";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int inBatch = 0;
                for (Map<String, String> row : groups.get(g.getKey())) {
                    for (int i = 0; i < groupColList.size(); i++) {
                        // каждый параметр гарантированно имеет значение — NULL нет
                        ps.setString(i + 1, row.get(groupColList.get(i)));
                    }
                    ps.addBatch();
                    if (++inBatch >= batchSize) {
                        ps.executeBatch();
                        inBatch = 0;
                    }
                }
                if (inBatch > 0) {
                    ps.executeBatch();
                    log.fine("Executed batch with " + inBatch + " rows");
                }
            }
        }
    }
}

