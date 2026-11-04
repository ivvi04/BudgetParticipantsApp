package ru.lakeevda.budgetparticipantsapp.db;

import ru.lakeevda.budgetparticipantsapp.config.ConfigLoader;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DatabaseConnection {
    private static final Logger logger = Logger.getLogger(DatabaseConnection.class.getName());
    private final String url;
    private final String username;
    private final String password;

    public DatabaseConnection(String url, String username, String password) {
        Properties props = ConfigLoader.load();
        this.url = props.getProperty("db.url");
        this.username = props.getProperty("db.username");
        this.password = props.getProperty("db.password");
        if (url == null || username == null || password == null) {
            throw new IllegalStateException("Database connection properties are not set correctly");
        }
    }

    public Connection getConnection() throws SQLException {
        logger.log(Level.INFO, "Opening database connection to {0} as user {1}", new Object[]{url, username});
        return DriverManager.getConnection(url, username, password);
    }
}
