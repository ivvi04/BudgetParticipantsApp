package ru.lakeevda.budgetparticipantsapp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.lakeevda.budgetparticipantsapp.mapper.JsonToXmlConverter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Архивирует результаты прошлых загрузок: json конвертируется в xml и сохраняется только xml. */
public final class ZipService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ZipService() { }

    public static void zipPreviousRuns(File rawDir, LocalDate from, LocalDate to) throws IOException {
        File[] files = rawDir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null || files.length == 0) {
            System.out.println("Nothing from previous runs to archive");
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
                    System.out.println("[WARN] Cannot convert to xml, keeping json: " + f.getName()
                            + " (" + e.getMessage() + ")");
                    continue;
                }
                if (!f.delete()) throw new IOException("Cannot delete " + f);
            }
        }
        System.out.println("Archived " + archived + " xml file(s) into " + zip.getName());
    }
}
