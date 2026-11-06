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
    private Properties cachedProperties;

    private ConfigLoader() {
    }

    /**
     * Получает единственный экземпляр ConfigLoader.
     *
     * @return единственный экземпляр ConfigLoader
     */
    public static ConfigLoader getInstance() {
        return INSTANCE;
    }

    /**
     * Загружает все свойства из файла конфигурации один раз.
     * При последующих вызовах возвращает кэшированные свойства.
     *
     * @return Properties объект (из кэша или загруженный заново)
     * @throws IllegalStateException если файл конфигурации не найден
     */
    public Properties load() {
        if (cachedProperties != null) {
            log.fine("Configuration already loaded, returning cached instance");
            return cachedProperties;
        }
        
        Properties props = new Properties();
        try (InputStream is = ConfigLoader.class.getClassLoader().getResourceAsStream(PROPERTIES_FILE)) {
            if (is == null) {
                throw new IllegalStateException("Properties file '" + PROPERTIES_FILE + "' not found in classpath");
            }
            props.load(is);
            log.info("Configuration loaded from " + PROPERTIES_FILE);
            cachedProperties = props;
        } catch (IOException e) {
            log.log(Level.SEVERE, "Failed to load properties from '" + PROPERTIES_FILE + "'", e);
            throw new RuntimeException("Failed to load properties from '" + PROPERTIES_FILE + "'", e);
        }
        return cachedProperties;
    }
}
