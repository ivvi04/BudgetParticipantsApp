package ru.lakeevda.budgetparticipantsapp.service;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.Assert.*;

/**
 * Тесты для {@link DataLoadService}.
 *
 * @author lakeev-da
 * @since 2026-11-10
 */
public class DataLoadServiceTest {

    private static final LocalDate FROM = LocalDate.of(2024, 1, 1);
    private static final LocalDate TO = LocalDate.of(2024, 1, 31);

    /**
     * Временная директория для файлов страниц.
     */
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    /**
     * Сервис без подключения к БД: проверяются только чистые методы.
     */
    private static DataLoadService service() {
        return new DataLoadService(null);
    }

    /**
     * Проверяет, что строка без отсутствующих колонок сохраняется.
     */
    @Test
    public void keepsRowsWithoutMissingColumns() {
        Set<String> tableCols = new HashSet<>(Arrays.asList("id", "name"));
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(row("id", "1", "name", "A"));

        List<Map<String, String>> fitting = DataLoadService.filterOverflowRows(rows, tableCols);

        assertEquals(1, fitting.size());
        assertSame(rows.get(0), fitting.get(0));
    }

    /**
     * Проверяет, что строка с непустым значением в отсутствующей колонке
     * отбрасывается и по ней пишется предупреждение.
     */
    @Test
    public void dropsRowWithNonEmptyMissingValue() {
        final List<String> messages = new ArrayList<>();
        Logger logger = Logger.getLogger(DataLoadService.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == Level.WARNING) {
                    messages.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            Set<String> tableCols = new HashSet<>(Arrays.asList("id", "name"));
            List<Map<String, String>> rows = new ArrayList<>();
            rows.add(row("id", "42", "name", "A", "extra", "x"));

            List<Map<String, String>> fitting = DataLoadService.filterOverflowRows(rows, tableCols);

            assertTrue(fitting.isEmpty());
            assertEquals(1, messages.size());
            assertEquals("Запись с id 42 пропущена по причине превышения количества колонок:"
                            + " требуется 3 из возможных 1595 (не поместилось 1).",
                    messages.get(0));
        } finally {
            logger.removeHandler(handler);
        }
    }

    /**
     * Проверяет, что пустое значение в отсутствующей колонке не мешает
     * сохранению строки.
     */
    @Test
    public void keepsRowWithOnlyEmptyMissingValues() {
        Set<String> tableCols = new HashSet<>(Arrays.asList("id", "name"));
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(row("id", "1", "name", "A", "extra", ""));
        rows.add(row("id", "2", "name", "B", "extra", null));

        List<Map<String, String>> fitting = DataLoadService.filterOverflowRows(rows, tableCols);

        assertEquals(2, fitting.size());
    }

    /**
     * Проверяет, что отбрасываются только «широкие» строки, а остальные
     * сохраняют исходный порядок.
     */
    @Test
    public void dropsOnlyOverflowRows() {
        Set<String> tableCols = new HashSet<>(Arrays.asList("id", "name"));
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(row("id", "1", "name", "A"));
        rows.add(row("id", "2", "name", "B", "extra", "x"));
        rows.add(row("id", "3", "name", "C"));

        List<Map<String, String>> fitting = DataLoadService.filterOverflowRows(rows, tableCols);

        assertEquals(2, fitting.size());
        assertEquals("1", fitting.get(0).get("id"));
        assertEquals("3", fitting.get(1).get("id"));
    }

    /**
     * Проверяет, что запись без id подписывается в предупреждении как «без id».
     */
    @Test
    public void marksRowWithoutIdInWarning() {
        final List<String> messages = new ArrayList<>();
        Logger logger = Logger.getLogger(DataLoadService.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == Level.WARNING) {
                    messages.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            DataLoadService.filterOverflowRows(
                    Collections.singletonList(row("name", "A", "extra", "x")),
                    new HashSet<>(Collections.singletonList("name")));

            assertEquals(1, messages.size());
            assertTrue(messages.get(0).startsWith("Запись с id <без id> пропущена"));
        } finally {
            logger.removeHandler(handler);
        }
    }

    /**
     * Проверяет чтение страницы с массивом в поле data и проставление периода.
     *
     * @throws Exception при ошибках ввода-вывода
     */
    @Test
    public void readsRowsFromDataArray() throws Exception {
        File file = write("{\"data\":[{\"id\":\"1\",\"info\":{\"name\":\"A\"}}]}");

        List<Map<String, String>> rows = service().readRows(file, FROM, TO);

        assertEquals(1, rows.size());
        assertEquals("1", rows.get(0).get("id"));
        assertEquals("A", rows.get(0).get("info_name"));
        assertEquals("2024-01-01", rows.get(0).get("last_update_from"));
        assertEquals("2024-01-31", rows.get(0).get("last_update_to"));
    }

    /**
     * Проверяет чтение страницы, корень которой является массивом.
     *
     * @throws Exception при ошибках ввода-вывода
     */
    @Test
    public void readsRowsFromRootArray() throws Exception {
        File file = write("[{\"id\":\"2\"}]");

        List<Map<String, String>> rows = service().readRows(file, FROM, TO);

        assertEquals(1, rows.size());
        assertEquals("2", rows.get(0).get("id"));
    }

    /**
     * Проверяет ошибку, если поле data отсутствует и корень не массив.
     *
     * @throws Exception при ошибках ввода-вывода
     */
    @Test(expected = SQLException.class)
    public void failsWhenDataMissingAndRootNotArray() throws Exception {
        File file = write("{\"pageCount\":1}");

        service().readRows(file, FROM, TO);
    }

    /**
     * Проверяет ошибку разбора некорректного JSON.
     *
     * @throws Exception при ошибках ввода-вывода
     */
    @Test(expected = SQLException.class)
    public void failsOnInvalidJson() throws Exception {
        File file = write("not a json");

        service().readRows(file, FROM, TO);
    }

    /**
     * Проверяет сборку запроса вставки с обновлением при конфликте.
     */
    @Test
    public void buildsInsertSqlWithConflictUpdate() {
        assertEquals("INSERT INTO denorm_data (id, name) VALUES (?, ?)"
                        + " ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name",
                service().buildInsertSql(Arrays.asList("id", "name")));
    }

    /**
     * Проверяет сборку запроса вставки без первичного ключа.
     */
    @Test
    public void buildsInsertSqlWithoutIdColumn() {
        assertEquals("INSERT INTO denorm_data (a, b) VALUES (?, ?)",
                service().buildInsertSql(Arrays.asList("a", "b")));
    }

    /**
     * Проверяет сборку запроса вставки только с первичным ключом.
     */
    @Test
    public void buildsInsertSqlWithOnlyIdColumn() {
        assertEquals("INSERT INTO denorm_data (id) VALUES (?)"
                        + " ON CONFLICT (id) DO NOTHING",
                service().buildInsertSql(Collections.singletonList("id")));
    }

    /**
     * Проверяет конструкцию ON CONFLICT для разных наборов колонок.
     */
    @Test
    public void buildsConflictClause() {
        assertEquals("", DataLoadService.buildConflictClause(Arrays.asList("a", "b")));
        assertEquals(" ON CONFLICT (id) DO NOTHING",
                DataLoadService.buildConflictClause(Collections.singletonList("id")));
        assertEquals(" ON CONFLICT (id) DO UPDATE SET a = EXCLUDED.a, b = EXCLUDED.b",
                DataLoadService.buildConflictClause(Arrays.asList("id", "a", "b")));
    }

    /**
     * Проверяет выбор непустых колонок в порядке списка.
     */
    @Test
    public void selectsPresentColumnsInListOrder() {
        Map<String, String> row = row("a", "1", "b", "2", "empty", "");
        List<String> columns = Arrays.asList("missing", "b", "empty", "a");

        assertEquals(Arrays.asList("b", "a"),
                DataLoadService.presentColumns(row, columns));
    }

    /**
     * Записывает файл страницы во временную директорию.
     *
     * @param content содержимое файла
     * @return созданный файл
     * @throws Exception при ошибках ввода-вывода
     */
    private File write(String content) throws Exception {
        File file = temporaryFolder.newFile("page.json");
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /**
     * Создаёт строку из пар «колонка → значение».
     *
     * @param pairs чередующиеся имена колонок и значения
     * @return строка данных
     */
    private static Map<String, String> row(String... pairs) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            row.put(pairs[i], pairs[i + 1]);
        }
        return row;
    }
}
