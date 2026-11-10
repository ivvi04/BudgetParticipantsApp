package ru.lakeevda.budgetparticipantsapp.db;

import ru.lakeevda.budgetparticipantsapp.config.ConfigLoader;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * Подключение к базе данных PostgreSQL.
 *
 * @author lakeev-da
 * @since 2026-11-06
 */
public class PostgresConnection implements DatabaseConnection {

    private static final Logger log = Logger.getLogger(PostgresConnection.class.getName());
    private static final PostgresConnection INSTANCE = new PostgresConnection();

    private final String url;
    private final String username;
    private final String password;

    /**
     * Конструктор-фабрика.
     * Загружает конфигурацию из application.properties.
     *
     * @throws IllegalStateException если конфигурация некорректна
     */
    private PostgresConnection() {
        this.url = ConfigLoader.getProperty("postgres.jdbc.url");
        this.username = ConfigLoader.getProperty("postgres.jdbc.username");
        this.password = ConfigLoader.getProperty("postgres.jdbc.password");
        if (url == null || username == null || password == null) {
            throw new IllegalStateException("Свойства подключения к базе данных заданы некорректно");
        }
    }

    /**
     * Получает единственное подключение к базе данных PostgreSQL.
     *
     * @return единственный экземпляр DatabaseConnection
     */
    public static PostgresConnection getInstance() {
        return INSTANCE;
    }

    /**
     * Возвращает имя базы данных PostgreSQL.
     *
     * @return "PostgreSQL"
     */
    @Override
    public String getDatabaseName() {
        return "PostgreSQL";
    }

    /**
     * Возвращает подключение к базе данных PostgreSQL.
     *
     * @return подключение к PostgreSQL
     * @throws SQLException при ошибках подключения
     */
    @Override
    public Connection getConnection() throws SQLException {
        log.info(String.format("Открытие подключения к базе данных %s под пользователем %s", url, username));
        return DriverManager.getConnection(url, username, password);
    }
}
