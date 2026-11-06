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
    private static final ConfigLoader INSTANCE = new ConfigLoader();
    
    /**
     * Кэширует загруженные свойства.
     * null до первого вызова load().
     */
    private final Properties properties;

    private ConfigLoader() {
        this.properties = new Properties();
        try (InputStream is = ConfigLoader.class.getClassLoader().getResourceAsStream(PROPERTIES_FILE)) {
            if (is == null) {
                throw new IllegalStateException("Properties file '" + PROPERTIES_FILE + "' not found in classpath");
            }
            this.properties.load(is);
            log.info("Configuration loaded from " + PROPERTIES_FILE);
        } catch (IOException e) {
            String message = "Failed to load properties from '" + PROPERTIES_FILE + "'";
            log.log(Level.INFO, message, e);
            throw new RuntimeException(message, e);
        }
    }

    /**
     * Получает единственный экземпляр ConfigLoader.
     *
     * @return единственный экземпляр ConfigLoader
     */
    public static ConfigLoader getInstance() {
        return INSTANCE;
    }


    public static String getProperty(String key, String defaultValue) {
        String value;
        try {
            value = getProperty(key);
        } catch (Exception e) {
            value = defaultValue;
        }
        return value;
    }

    public static String getProperty(String key) {
        if (INSTANCE.properties.containsKey(key)) {
            return INSTANCE.properties.getProperty(key);
        } else {
            String message = "Property with key '" + key + "' not found";
            log.log(Level.INFO, message);
            throw new RuntimeException(message);
        }
    }

}
