package ru.lakeevda.budgetparticipantsapp.db;

import java.sql.Connection;
import java.sql.SQLException;

public interface DatabaseConnection {

    String getDatabaseName();

    Connection getConnection() throws SQLException;
}
