package ru.lakeevda.budgetparticipantsapp.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Лоадер конфигурации.
 * Загружает свойства из application.properties, расположенного в classpath.
 */
public class ConfigLoader {
    private static final Logger log = Logger.getLogger(ConfigLoader.class.getName());
    private static final String PROPERTIES_FILE = "application.properties";

    /**
     * Загружает все свойства из файла конфигурации.
     *
     * @return Properties объект
     * @throws IllegalStateException если файл конфигурации не найден
     */
    public static Properties load() {
        Properties props = new Properties();
        try (InputStream is = ConfigLoader.class.getClassLoader().getResourceAsStream(PROPERTIES_FILE)) {
            if (is == null) {
                throw new IllegalStateException("Properties file '" + PROPERTIES_FILE + "' not found in classpath");
            }
            props.load(is);
            log.info("Configuration loaded from " + PROPERTIES_FILE);
        } catch (IOException e) {
            log.log(Level.SEVERE, "Failed to load properties from '" + PROPERTIES_FILE + "'", e);
            throw new RuntimeException("Failed to load properties from '" + PROPERTIES_FILE + "'", e);
        }
        return props;
    }
}
