package ru.lakeevda.budgetparticipantsapp.service;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Тесты для {@link ZipService}.
 *
 * @author lakeev-da
 * @since 2026-11-06
 */
public class ZipServiceTest {

    private static final LocalDate FROM = LocalDate.of(2024, 1, 1);
    private static final LocalDate TO = LocalDate.of(2024, 1, 31);

    /** Временная директория для тестов. */
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    /**
     * Проверяет конвертацию JSON в XML, упаковку и удаление оригинала.
     *
     * @throws Exception при ошибках ввода-вывода
     */
    @Test
    public void archivesJsonAsXmlAndDeletesSource() throws Exception {
        File raw = temporaryFolder.newFolder("raw");
        File json = write(raw, "page_1.json", "{\"id\":\"1\",\"name\":\"A\"}");

        ZipService.zipPreviousRuns(raw, FROM, TO);

        File zip = new File(raw, "archive_2024-01-01_2024-01-31.zip");
        assertTrue(zip.isFile());
        assertFalse(json.exists());

        Map<String, String> entries = readZip(zip);
        assertEquals(1, entries.size());
        String xml = entries.get("page_1.xml");
        assertTrue(xml.contains("<id>1</id>"));
        assertTrue(xml.contains("<name>A</name>"));
    }

    /**
     * Проверяет, что файл с некорректным JSON сохраняется, а архив создаётся.
     *
     * @throws Exception при ошибках ввода-вывода
     */
    @Test
    public void keepsInvalidJsonFile() throws Exception {
        File raw = temporaryFolder.newFolder("raw");
        File bad = write(raw, "bad.json", "not a json");

        ZipService.zipPreviousRuns(raw, FROM, TO);

        File zip = new File(raw, "archive_2024-01-01_2024-01-31.zip");
        assertTrue(zip.isFile());
        assertTrue(bad.exists());
        assertNull(readZip(zip).get("bad.xml"));
    }

    /**
     * Проверяет, что при отсутствии json-файлов архив не создаётся.
     *
     * @throws Exception при ошибках ввода-вывода
     */
    @Test
    public void doesNotCreateArchiveWhenNoJsonFiles() throws Exception {
        File raw = temporaryFolder.newFolder("raw");
        write(raw, "note.txt", "text");

        ZipService.zipPreviousRuns(raw, FROM, TO);

        assertFalse(new File(raw, "archive_2024-01-01_2024-01-31.zip").exists());
    }

    /**
     * Проверяет, что не-json файлы не удаляются и не попадают в архив.
     *
     * @throws Exception при ошибках ввода-вывода
     */
    @Test
    public void ignoresNonJsonFiles() throws Exception {
        File raw = temporaryFolder.newFolder("raw");
        File json = write(raw, "page.json", "{\"id\":1}");
        File txt = write(raw, "note.txt", "text");

        ZipService.zipPreviousRuns(raw, FROM, TO);

        assertFalse(json.exists());
        assertTrue(txt.exists());
        Map<String, String> entries = readZip(
                new File(raw, "archive_2024-01-01_2024-01-31.zip"));
        assertEquals(1, entries.size());
        assertTrue(entries.containsKey("page.xml"));
    }

    private static File write(File dir, String name, String content) throws IOException {
        File file = new File(dir, name);
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static Map<String, String> readZip(File zip) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int read;
                while ((read = zis.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                result.put(entry.getName(),
                        new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        }
        return result;
    }
}
