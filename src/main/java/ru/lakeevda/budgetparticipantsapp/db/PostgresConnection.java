package ru.lakeevda.budgetparticipantsapp.db;

import ru.lakeevda.budgetparticipantsapp.config.ConfigLoader;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Подключение к базе данных Postgres.
 */
public class PostgresConnection implements DatabaseConnection {
    private static final Logger logger = Logger.getLogger(PostgresConnection.class.getName());
    private static final PostgresConnection INSTANCE = new PostgresConnection();
    private final String url;
    private final String username;
    private final String password;

    private PostgresConnection() {
        Properties props = ConfigLoader.getInstance().load();
        this.url = props.getProperty("postgres.url");
        this.username = props.getProperty("postgres.username");
        this.password = props.getProperty("postgres.password");
        if (url == null || username == null || password == null) {
            throw new IllegalStateException("Database connection properties are not set correctly");
        }
    }

    /**
     * Получает единственное подключение к базе данных.
     *
     * @return единственный экземпляр DatabaseConnection
     */
    public static PostgresConnection getInstance() {
        return INSTANCE;
    }

    @Override
    public String getDatabaseName() {
        return "PostgreSQL";
    }

    @Override
    public Connection getConnection() throws SQLException {
        logger.log(Level.INFO, "Opening database connection to %s as user %s", new Object[]{url, username});
        return DriverManager.getConnection(url, username, password);
    }
}
