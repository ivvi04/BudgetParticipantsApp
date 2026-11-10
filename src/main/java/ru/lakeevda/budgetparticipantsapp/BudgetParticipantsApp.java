package ru.lakeevda.budgetparticipantsapp;

import ru.lakeevda.budgetparticipantsapp.client.ApiClient;
import ru.lakeevda.budgetparticipantsapp.config.ConfigLoader;
import ru.lakeevda.budgetparticipantsapp.db.ClickHouseConnection;
import ru.lakeevda.budgetparticipantsapp.db.PostgresConnection;
import ru.lakeevda.budgetparticipantsapp.service.ClickHouseTransferService;
import ru.lakeevda.budgetparticipantsapp.service.DataLoadService;
import ru.lakeevda.budgetparticipantsapp.service.ZipService;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Основное приложение для загрузки данных реестра участников бюджетного процесса.
 *
 * @author lakeev-da
 * @since 2026-11-06
 */
public final class BudgetParticipantsApp {

    private static final Logger log = Logger.getLogger(BudgetParticipantsApp.class.getName());

    /**
     * Точка входа в приложение.
     *
     * @param args аргументы командной строки (формат дат: yyyy-MM-dd)
     */
    public static void main(String[] args) {
        log.info("Запуск BudgetParticipantsApp");
        try {
            LocalDate from = LocalDate.parse(args[0]);
            LocalDate to = LocalDate.parse(args[1]);
            validatePeriod(from, to);
            run(from, to);
        } catch (DateTimeParseException e) {
            log.log(Level.INFO, "Некорректный формат даты: " + e.getMessage(), e);
            System.exit(1);
        } catch (IllegalArgumentException e) {
            log.severe(e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            log.log(Level.INFO, "Ошибка выполнения: " + e.getMessage(), e);
            System.exit(2);
        }
    }

    /**
     * Проверяет корректность периода загрузки.
     *
     * @param from начальная дата периода
     * @param to   конечная дата периода
     * @throws IllegalArgumentException если начальная дата позже конечной
     */
    static void validatePeriod(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("Дата lastUpdateFrom не может быть позже lastUpdateTo");
        }
    }

    /**
     * Выполняет полный цикл: скачивание, загрузка, перенос в ClickHouse, архивация.
     *
     * @param from начальная дата периода
     * @param to   конечная дата периода
     * @throws IOException  при ошибках ввода-вывода
     * @throws SQLException при ошибках работы с БД
     */
    private static void run(LocalDate from, LocalDate to) throws IOException, SQLException {
        // Директория для хранения сырых данных.
        String rawDir = ConfigLoader.getProperty("storage.rawDir", "data/raw");
        File dir = new File(rawDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // Архивирует прошлые запуски.
        ZipService.zipPreviousRuns(dir);

        // Скачивает все страницы API.
        ApiClient apiClient = new ApiClient();
        List<File> pageFiles = apiClient.fetchAllPages(from, to, dir);

        // Подключение к PostgreSQL.
        PostgresConnection postgresConnection = PostgresConnection.getInstance();

        // Загружает данные в PostgreSQL.
        DataLoadService dataLoadService = new DataLoadService(postgresConnection);
        dataLoadService.load(pageFiles, from, to);

        // Подключение к ClickHouse.
        ClickHouseConnection clickHouseConnection = ClickHouseConnection.getInstance();
        ClickHouseTransferService clickHouseTransferService =
                new ClickHouseTransferService(clickHouseConnection, postgresConnection);
        if (clickHouseTransferService.isEnabled()) {
            clickHouseTransferService.transfer();
        }

        log.info(String.format("Готово: файлов страниц %d, таблица %s, период %s..%s",
                pageFiles.size(), ConfigLoader.getProperty("db.table"), from, to));
    }
}
