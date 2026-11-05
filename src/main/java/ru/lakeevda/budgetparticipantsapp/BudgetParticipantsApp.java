package ru.lakeevda.budgetparticipantsapp;

import ru.lakeevda.budgetparticipantsapp.client.ApiClient;
import ru.lakeevda.budgetparticipantsapp.config.ConfigLoader;
import ru.lakeevda.budgetparticipantsapp.db.DatabaseConnection;
import ru.lakeevda.budgetparticipantsapp.service.ClickHouseTransferService;
import ru.lakeevda.budgetparticipantsapp.service.DataLoadService;
import ru.lakeevda.budgetparticipantsapp.service.ZipService;

import java.io.*;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Основное приложение для загрузки данных реестра участников бюджетного процесса.
 * Скачивает данные из API, загружает в PostgreSQL, архивирует прошлые запуски.
 */
public final class BudgetParticipantsApp {
    private static final Logger log = Logger.getLogger(BudgetParticipantsApp.class.getName());

    public static void main(String[] args) {
        log.info("Starting BudgetParticipantsApp");
        try {
            LocalDate from =
                    LocalDate.parse("2024-01-01");
            LocalDate to =
                    LocalDate.parse("2024-01-31");
            if (from.isAfter(to)) {
                log.severe("lastUpdateFrom must not be after lastUpdateTo");
                System.exit(1);
            }
            run(from, to);
        } catch (DateTimeParseException e) {
            log.log(Level.SEVERE, "Invalid date format: " + e.getMessage(), e);
            System.exit(1);
        } catch (Exception e) {
            log.log(Level.SEVERE, "Failed: " + e.getMessage(), e);
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
        ClickHouseTransferService ch = new ClickHouseTransferService(props);
        if (ch.isEnabled(props)) {
            ch.transfer(url, username, password, table);
        }

        log.info(String.format("Done: %d page file(s), table %s, period %s..%s",
                pageFiles.size(), table, from, to));
    }
}
