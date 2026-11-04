package ru.lakeevda.budgetparticipantsapp.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Загружает свойства из application.properties, расположенного в classpath.
 */
public class ConfigLoader {
    private static final String PROPERTIES_FILE = "application.properties";

    public static Properties load() {
        Properties props = new Properties();
        try (InputStream is = ConfigLoader.class.getClassLoader().getResourceAsStream(PROPERTIES_FILE)) {
            if (is == null) {
                throw new IllegalStateException("Properties file '" + PROPERTIES_FILE + "' not found in classpath");
            }
            props.load(is);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load properties from '" + PROPERTIES_FILE + "'", e);
        }
        return props;
    }
}
