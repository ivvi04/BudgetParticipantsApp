package ru.lakeevda.budgetparticipantsapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.lakeevda.budgetparticipantsapp.mapper.JsonToXmlConverter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Сервис для архивации результатов прошлых загрузок.
 * JSON конвертируется в XML и сохраняется только в архиве.
 */
public final class ZipService {
    private static final Logger log = Logger.getLogger(ZipService.class.getName());

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ZipService() { }

    /**
     * Архивирует результаты прошлых загрузок в ZIP-файл.
     *
     * @param rawDir директория с сырыми JSON файлами
     * @param from начальная дата периода
     * @param to конечная дата периода
     * @throws IOException при ошибках работы с файловой системой
     */
    public static void zipPreviousRuns(File rawDir, LocalDate from, LocalDate to) throws IOException {
        log.info(String.format("Archiving previous runs for period %s..%s", from, to));
        File[] files = rawDir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null || files.length == 0) {
            log.info("Nothing from previous runs to archive");
            return;
        }
        File zip = new File(rawDir, String.format("archive_%s_%s.zip", from, to));
        int archived = 0;
        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(zip, true)))) {
            for (File f : files) {
                String xmlName = f.getName().replaceAll("\\.json$", "") + ".xml";
                try {
                    JsonNode node = MAPPER.readTree(f);
                    zos.putNextEntry(new ZipEntry(xmlName));
                    zos.write(JsonToXmlConverter.toXml(node).getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                    archived++;
                } catch (Exception e) {
                    log.log(Level.WARNING, "Cannot convert to xml, keeping json: " + f.getName()
                            + " (" + e.getMessage() + ")", e);
                    continue;
                }
                if (!f.delete()) throw new IOException("Cannot delete " + f);
            }
        }
        log.info("Archived " + archived + " xml file(s) into " + zip.getName());
    }
}
