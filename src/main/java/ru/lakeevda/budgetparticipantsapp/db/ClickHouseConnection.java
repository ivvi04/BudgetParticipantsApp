package ru.lakeevda.budgetparticipantsapp.db;

import ru.lakeevda.budgetparticipantsapp.config.ConfigLoader;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Подключение к базе данных ClickHouse.
 * Реализует интерфейс DatabaseConnection.
 *
 * @author lakeev-da
 * @since 2026-11-06
 */
public class ClickHouseConnection implements DatabaseConnection {

    private static final Logger logger = Logger.getLogger(ClickHouseConnection.class.getName());
    private static final ClickHouseConnection INSTANCE = new ClickHouseConnection();

    private final String url;
    private final String username;
    private final String password;

    /**
     * Конструктор-фабрика.
     * Загружает конфигурацию из application.properties.
     * @throws IllegalStateException если конфигурация некорректна
     */
    private ClickHouseConnection() {
        this.url = ConfigLoader.getProperty("clickhouse.url");
        this.username = ConfigLoader.getProperty("clickhouse.username");
        this.password = ConfigLoader.getProperty("clickhouse.password");
        if (url == null || username == null || password == null) {
            throw new IllegalStateException("Database connection properties are not set correctly");
        }
    }

    /**
     * Получает единственное подключение к базе данных ClickHouse.
     *
     * @return единственный экземпляр DatabaseConnection
     */
    public static ClickHouseConnection getInstance() {
        return INSTANCE;
    }

    /**
     * Возвращает имя базы данных ClickHouse.
     *
     * @return "ClickHouse"
     */
    @Override
    public String getDatabaseName() {
        return "ClickHouse";
    }

    /**
     * Возвращает подключение к базе данных ClickHouse.
     *
     * @return подключение к ClickHouse
     * @throws SQLException при ошибках подключения
     */
    @Override
    public Connection getConnection() throws SQLException {
        logger.log(Level.INFO, "Opening database connection to %s as user %s", new Object[]{url, username});
        return DriverManager.getConnection(url, username, password);
    }
}
