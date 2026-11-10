package ru.lakeevda.budgetparticipantsapp.service;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

/**
 * Тесты для {@link ZipService}.
 *
 * @author lakeev-da
 * @since 2026-11-06
 */
public class ZipServiceTest {

    /**
     * Временная директория для тестов.
     */
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
        File json = write(raw, "page_2024-01-01_2024-01-31_00001.json",
                "{\"id\":\"1\",\"name\":\"A\"}");

        ZipService.zipPreviousRuns(raw);

        File zip = new File(raw, "archive_2024-01-01_2024-01-31.zip");
        assertTrue(zip.isFile());
        assertFalse(json.exists());

        Map<String, String> entries = readZip(zip);
        assertEquals(1, entries.size());
        String xml = entries.get("page_2024-01-01_2024-01-31_00001.xml");
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

        ZipService.zipPreviousRuns(raw);

        File zip = new File(raw, "archive_other.zip");
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

        ZipService.zipPreviousRuns(raw);

        File[] archives = raw.listFiles((d, name) -> name.endsWith(".zip"));
        assertNotNull(archives);
        assertEquals(0, archives.length);
    }

    /**
     * Проверяет, что не-json файлы не удаляются и не попадают в архив.
     *
     * @throws Exception при ошибках ввода-вывода
     */
    @Test
    public void ignoresNonJsonFiles() throws Exception {
        File raw = temporaryFolder.newFolder("raw");
        File json = write(raw, "page_2024-01-01_2024-01-31_00001.json", "{\"id\":1}");
        File txt = write(raw, "note.txt", "text");

        ZipService.zipPreviousRuns(raw);

        assertFalse(json.exists());
        assertTrue(txt.exists());
        Map<String, String> entries = readZip(
                new File(raw, "archive_2024-01-01_2024-01-31.zip"));
        assertEquals(1, entries.size());
        assertTrue(entries.containsKey("page_2024-01-01_2024-01-31_00001.xml"));
    }

    /**
     * Проверяет, что архив называется по периоду из имени файла страницы.
     *
     * @throws Exception при ошибках ввода-вывода
     */
    @Test
    public void namesArchiveByPeriodFromFileName() throws Exception {
        File raw = temporaryFolder.newFolder("raw");
        write(raw, "page_2023-05-01_2023-05-31_00001.json", "{\"id\":\"1\"}");

        ZipService.zipPreviousRuns(raw);

        assertTrue(new File(raw, "archive_2023-05-01_2023-05-31.zip").isFile());
        assertFalse(new File(raw, "archive_other.zip").exists());
    }

    /**
     * Проверяет, что файлы с именами вне шаблона попадают в отдельный архив
     * и не приписываются к чужому периоду.
     *
     * @throws Exception при ошибках ввода-вывода
     */
    @Test
    public void putsUnknownFileNamesIntoOtherArchive() throws Exception {
        File raw = temporaryFolder.newFolder("raw");
        write(raw, "page_dump.json", "{\"id\":\"1\"}");

        ZipService.zipPreviousRuns(raw);

        Map<String, String> entries = readZip(new File(raw, "archive_other.zip"));
        assertEquals(1, entries.size());
        assertTrue(entries.containsKey("page_dump.xml"));
    }

    /**
     * Проверяет, что файлы разных периодов раскладываются по своим архивам.
     *
     * @throws Exception при ошибках ввода-вывода
     */
    @Test
    public void splitsFilesBetweenPeriodArchives() throws Exception {
        File raw = temporaryFolder.newFolder("raw");
        write(raw, "page_2023-05-01_2023-05-31_00001.json", "{\"id\":\"1\"}");
        write(raw, "page_2024-01-01_2024-01-31_00001.json", "{\"id\":\"2\"}");

        ZipService.zipPreviousRuns(raw);

        Map<String, String> may = readZip(new File(raw, "archive_2023-05-01_2023-05-31.zip"));
        Map<String, String> january = readZip(new File(raw, "archive_2024-01-01_2024-01-31.zip"));
        assertEquals(1, may.size());
        assertEquals(1, january.size());
        assertTrue(may.containsKey("page_2023-05-01_2023-05-31_00001.xml"));
        assertTrue(january.containsKey("page_2024-01-01_2024-01-31_00001.xml"));
    }

    /**
     * Проверяет, что повторная архивация за тот же период сохраняет
     * ранее заархивированные страницы и не создаёт дубликатов.
     *
     * @throws Exception при ошибках ввода-вывода
     */
    @Test
    public void keepsPreviousEntriesWhenArchivingAgain() throws Exception {
        File raw = temporaryFolder.newFolder("raw");
        write(raw, "page_2024-01-01_2024-01-31_00001.json", "{\"id\":\"1\"}");
        ZipService.zipPreviousRuns(raw);

        write(raw, "page_2024-01-01_2024-01-31_00002.json", "{\"id\":\"2\"}");
        ZipService.zipPreviousRuns(raw);

        File zip = new File(raw, "archive_2024-01-01_2024-01-31.zip");
        Map<String, String> entries = readZip(zip);
        assertEquals(2, entries.size());
        assertTrue(entries.containsKey("page_2024-01-01_2024-01-31_00001.xml"));
        assertTrue(entries.containsKey("page_2024-01-01_2024-01-31_00002.xml"));

        try (ZipFile zipFile = new ZipFile(zip)) {
            assertEquals(2, zipFile.size());
            assertNotNull(zipFile.getEntry("page_2024-01-01_2024-01-31_00001.xml"));
            assertNotNull(zipFile.getEntry("page_2024-01-01_2024-01-31_00002.xml"));
        }
        assertEquals(0, tempFiles(raw).length);
    }

    /**
     * Проверяет, что запись с тем же именем заменяется новой версией,
     * а не дублируется.
     *
     * @throws Exception при ошибках ввода-вывода
     */
    @Test
    public void replacesEntryWithSameName() throws Exception {
        File raw = temporaryFolder.newFolder("raw");
        write(raw, "page_2024-01-01_2024-01-31_00001.json", "{\"id\":\"1\"}");
        ZipService.zipPreviousRuns(raw);

        write(raw, "page_2024-01-01_2024-01-31_00001.json", "{\"id\":\"2\"}");
        ZipService.zipPreviousRuns(raw);

        File zip = new File(raw, "archive_2024-01-01_2024-01-31.zip");
        Map<String, String> entries = readZip(zip);
        assertEquals(1, entries.size());
        String xml = entries.get("page_2024-01-01_2024-01-31_00001.xml");
        assertTrue(xml.contains("<id>2</id>"));
        assertFalse(xml.contains("<id>1</id>"));
        try (ZipFile zipFile = new ZipFile(zip)) {
            assertEquals(1, zipFile.size());
        }
    }

    /**
     * Проверяет, что исходный JSON удаляется только после успешной
     * пересборки архива.
     *
     * @throws Exception при ошибках ввода-вывода
     */
    @Test
    public void keepsSourceWhenArchiveCannotBeWritten() throws Exception {
        File raw = temporaryFolder.newFolder("raw");
        File json = write(raw, "page_2024-01-01_2024-01-31_00001.json", "{\"id\":\"1\"}");
        File archive = new File(raw, "archive_2024-01-01_2024-01-31.zip");
        File temp = new File(raw, archive.getName() + ".tmp");
        // Каталог на месте временного файла делает запись архива невозможной
        assertTrue(temp.mkdirs());

        try {
            ZipService.zipPreviousRuns(raw);
            fail("Ожидалась ошибка записи архива");
        } catch (IOException expected) {
            assertTrue(json.exists());
            assertFalse(archive.exists());
        }
    }

    /**
     * Проверяет, что запись-каталог прежнего архива пропускается без ошибки,
     * а обычные записи сохраняются.
     *
     * @throws Exception при ошибках ввода-вывода
     */
    @Test
    public void skipsDirectoryEntriesOfPreviousArchive() throws Exception {
        File raw = temporaryFolder.newFolder("raw");
        File zip = new File(raw, "archive_2024-01-01_2024-01-31.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("docs/"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("old.xml"));
            zos.write("<old/>".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        write(raw, "page_2024-01-01_2024-01-31_00001.json", "{\"id\":\"1\"}");

        ZipService.zipPreviousRuns(raw);

        Map<String, String> entries = readZip(zip);
        assertFalse(entries.containsKey("docs/"));
        assertTrue(entries.containsKey("old.xml"));
        assertTrue(entries.containsKey("page_2024-01-01_2024-01-31_00001.xml"));
    }

    private static File[] tempFiles(File dir) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".tmp"));
        return files == null ? new File[0] : files;
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

