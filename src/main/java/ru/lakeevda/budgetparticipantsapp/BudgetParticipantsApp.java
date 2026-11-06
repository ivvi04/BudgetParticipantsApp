package ru.lakeevda.budgetparticipantsapp;

import ru.lakeevda.budgetparticipantsapp.client.ApiClient;
import ru.lakeevda.budgetparticipantsapp.config.ConfigLoader;
import ru.lakeevda.budgetparticipantsapp.db.ClickHouseConnection;
import ru.lakeevda.budgetparticipantsapp.db.DatabaseConnection;
import ru.lakeevda.budgetparticipantsapp.db.PostgresConnection;
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

        String rawDir = ConfigLoader.getProperty("storage.rawDir", "data/raw");
        File dir = new File(rawDir);
        if (!dir.exists()) dir.mkdirs();

        // 3. Ранее скачанные данные -> json + производные xml в zip
        ZipService.zipPreviousRuns(dir, from, to);

        // 1. Скачиваем все страницы
        ApiClient client = new ApiClient();
        List<File> pageFiles =
                client.fetchAllPages(from, to, dir);

        PostgresConnection postgresConnection = PostgresConnection.getInstance();

        // 2. Денормализованная загрузка в PostgreSQL (перезапись по ключам периода)
        DataLoadService db = new DataLoadService(postgresConnection,
                ConfigLoader.getProperty("postgres.table"));
        db.load(pageFiles, from, to);

        // Плюс 2: перенос из PostgreSQL в ClickHouse
        ClickHouseConnection clickHouseConnection = ClickHouseConnection.getInstance();
        ClickHouseTransferService ch = new ClickHouseTransferService(clickHouseConnection,
                ConfigLoader.getProperty("clickhouse.table"));
        if (ch.isEnabled()) {
            ch.transfer(postgresConnection, ConfigLoader.getProperty("db.table"));
        }

        log.info(String.format("Done: %d page file(s), table %s, period %s..%s",
                pageFiles.size(), ConfigLoader.getProperty("db.table"), from, to));
    }
}
