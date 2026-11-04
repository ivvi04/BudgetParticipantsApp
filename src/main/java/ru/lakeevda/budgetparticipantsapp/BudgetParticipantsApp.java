package ru.lakeevda.budgetparticipantsapp;

import ru.lakeevda.budgetparticipantsapp.client.ApiClient;
import ru.lakeevda.budgetparticipantsapp.config.ConfigLoader;
import ru.lakeevda.budgetparticipantsapp.db.DatabaseConnection;
import ru.lakeevda.budgetparticipantsapp.service.DataLoadService;
import ru.lakeevda.budgetparticipantsapp.service.ZipService;

import java.io.*;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

public final class BudgetParticipantsApp {

    public static void main(String[] args) {
//        if (args.length != 2) {
//            System.err.println("Usage: java -jar data-loader.jar <lastUpdateFrom> <lastUpdateTo>");
//            System.err.println("       dates in ISO format, e.g. 2024-01-01");
//            System.exit(1);
//        }
        try {
            LocalDate from =
                    LocalDate.parse("2024-01-01");
//                    LocalDate.parse(args[0]);
            LocalDate to =
                    LocalDate.parse("2024-01-31");
//                    LocalDate.parse(args[1]);
            if (from.isAfter(to)) {
                System.err.println("lastUpdateFrom must not be after lastUpdateTo");
                System.exit(1);
            }
            run(from, to);
        } catch (DateTimeParseException e) {
            System.err.println("Invalid date format: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(2);
        }
    }

    private static void run(LocalDate from, LocalDate to) throws IOException, SQLException {
        Properties props = ConfigLoader.load();

        String rawDir = props.getProperty("storage.rawDir", "data/raw");
        File dir = new File(rawDir);
        if (!dir.exists()) dir.mkdirs();

        // 3. Ранее скачанные данные -> json + производные xml в zip
        ZipService.zipPreviousRuns(dir, from, to);

        // 1. Скачиваем все страницы
        ApiClient client = new ApiClient(props);
        List<File> pageFiles =
//                Arrays.asList(new File("data/raw/page_2024-01-01_2024-01-31_00001.json"),
//                        new File("data/raw/page_2024-01-01_2024-01-31_00002.json"));
//                Collections.singletonList(new File("data/raw/page_test.json"));
                client.fetchAllPages(from, to, dir);

        String url = props.getProperty("db.url");
        String username = props.getProperty("db.username");
        String password = props.getProperty("db.password");
        String table = props.getProperty("db.table");
        DatabaseConnection connection = new DatabaseConnection(url, username, password);

        // 2. Денормализованная загрузка в PostgreSQL (перезапись по ключам периода)
        DataLoadService db = new DataLoadService(connection, table);
        db.load(pageFiles, from, to);

        // Плюс 2: перенос из PostgreSQL в ClickHouse
//        ClickHouseTransferService ch = new ClickHouseTransferService(cfg);
//        if (ch.isEnabled(cfg)) {
//            ch.transfer(cfg.getProperty("db.url"),
//                    cfg.getProperty("db.user"),
//                    cfg.getProperty("db.password"),
//                    cfg.getProperty("db.table", "denorm_data"));
//        }

        System.out.printf("Done: %d page file(s), table %s, period %s..%s%n",
                pageFiles.size(), props.getProperty("db.table"), from, to);
    }
}
