package ru.lakeevda.budgetparticipantsapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.lakeevda.budgetparticipantsapp.mapper.JsonToXmlConverter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Сервис для архивации результатов прошлых загрузок.
 *
 * @author lakeev-da
 * @since 2026-11-06
 */
public final class ZipService {

    private static final Logger log = Logger.getLogger(ZipService.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ARCHIVE_ZIP_FORMAT = "archive_%s_%s.zip";

    private static final String OTHER_ZIP = "archive_other.zip";
    private static final String TEMP_SUFFIX = ".tmp";
    private static final Pattern PAGE_FILE_NAME =
            Pattern.compile("^page_(\\d{4}-\\d{2}-\\d{2})_(\\d{4}-\\d{2}-\\d{2})_\\d+\\.json$");
    private static final Pattern JSON_SUFFIX = Pattern.compile("\\.json$");
    private static final int BUFFER_SIZE = 8192;

    /**
     * Частный конструктор.
     */
    private ZipService() {
    }

    /**
     * Архивирует результаты прошлых загрузок в ZIP-файлы.
     *
     * @param rawDir директория с сырыми JSON файлами
     * @throws IOException при ошибках работы с файловой системой
     */
    public static void zipPreviousRuns(File rawDir) throws IOException {
        log.info("Архивация результатов прошлых запусков из " + rawDir);
        File[] files = rawDir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null || files.length == 0) {
            log.info("Нет данных прошлых запусков для архивации");
            return;
        }

        Map<String, List<File>> groups = new LinkedHashMap<>();
        for (File f : files) {
            String archiveName = archiveName(f);
            List<File> group = groups.computeIfAbsent(archiveName, k -> new ArrayList<>());
            group.add(f);
        }

        for (Map.Entry<String, List<File>> entry : groups.entrySet()) {
            writeArchive(rawDir, entry.getKey(), entry.getValue());
        }
    }

    /**
     * Определяет имя архива для файла страницы.
     *
     * @param file файл страницы
     * @return имя архива
     */
    private static String archiveName(File file) {
        Matcher matcher = PAGE_FILE_NAME.matcher(file.getName());
        if (matcher.matches()) {
            return String.format(ARCHIVE_ZIP_FORMAT, matcher.group(1), matcher.group(2));
        }
        log.warning("Имя файла " + file.getName() + " не соответствует шаблону "
                + PAGE_FILE_NAME.pattern() + ", файл будет заархивирован в " + OTHER_ZIP);
        return OTHER_ZIP;
    }

    /**
     * Пересобирает архив одной группы файлов.
     *
     * @param rawDir      директория с сырыми JSON файлами
     * @param archiveName имя архива
     * @param files       файлы, помещаемые в архив
     * @throws IOException при ошибках работы с файловой системой
     */
    private static void writeArchive(File rawDir, String archiveName, List<File> files) throws IOException {
        File zip = new File(rawDir, archiveName);
        File temp = new File(rawDir, zip.getName() + TEMP_SUFFIX);
        Set<String> newNames = new HashSet<>();
        for (File f : files) {
            newNames.add(xmlName(f));
        }

        List<File> archived = new ArrayList<>();
        int skippedExisting = 0;
        try {
            try (ZipOutputStream zos = new ZipOutputStream(
                    new BufferedOutputStream(new FileOutputStream(temp)))) {
                if (zip.isFile()) {
                    try (ZipFile previous = new ZipFile(zip)) {
                        skippedExisting = copyPreviousEntries(previous, zos, newNames);
                    }
                }
                for (File f : files) {
                    if (appendXml(zos, f)) {
                        archived.add(f);
                    }
                }
            }
            Files.move(temp.toPath(), zip.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            temp.delete();
            throw e;
        }

        int failedToDelete = 0;
        for (File f : archived) {
            if (!f.delete()) {
                failedToDelete++;
                log.warning("Не удалось удалить исходный JSON: " + f.getName());
            }
        }
        log.info(String.format("Архивировано XML-файлов: %d в %s (заменено прежних версий: %d)%s",
                archived.size(), zip.getName(), skippedExisting,
                failedToDelete > 0 ? ", не удалено исходных: " + failedToDelete : ""));
    }

    /**
     * Переносит записи прежнего архива в новый архив.
     *
     * @param previous прежний архив
     * @param zos      поток нового архива
     * @param newNames имена записей новой версии
     * @return число пропущенных (заменённых) записей
     * @throws IOException при ошибках чтения прежнего архива
     */
    private static int copyPreviousEntries(ZipFile previous, ZipOutputStream zos,
                                           Set<String> newNames) throws IOException {
        int skipped = 0;
        Enumeration<? extends ZipEntry> entries = previous.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) {
                continue;
            }
            if (newNames.contains(entry.getName())) {
                skipped++;
                continue;
            }
            zos.putNextEntry(new ZipEntry(entry.getName()));
            try (InputStream in = previous.getInputStream(entry)) {
                copy(in, zos);
            }
            zos.closeEntry();
        }
        return skipped;
    }

    /**
     * Конвертирует JSON-файл в XML и добавляет его записью в архив.
     *
     * @param zos  поток архива
     * @param file JSON-файл
     * @return {@code true}, если файл добавлен в архив
     * @throws IOException при ошибках записи архива
     */
    private static boolean appendXml(ZipOutputStream zos, File file) throws IOException {
        String xml;
        try {
            JsonNode node = MAPPER.readTree(file);
            xml = JsonToXmlConverter.toXml(node);
        } catch (Exception e) {
            log.log(Level.WARNING, "Не удалось конвертировать в XML, JSON-файл сохранён: " + file.getName()
                    + " (" + e.getMessage() + ")", e);
            return false;
        }
        zos.putNextEntry(new ZipEntry(xmlName(file)));
        zos.write(xml.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
        return true;
    }

    /**
     * Возвращает имя XML-записи для JSON-файла.
     *
     * @param file JSON-файл
     * @return имя записи в архиве
     */
    private static String xmlName(File file) {
        return JSON_SUFFIX.matcher(file.getName()).replaceAll("") + ".xml";
    }

    /**
     * Перекладывает байты из входного потока в выходной.
     *
     * @param in  входной поток
     * @param out выходной поток
     * @throws IOException при ошибках ввода-вывода
     */
    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }
}
