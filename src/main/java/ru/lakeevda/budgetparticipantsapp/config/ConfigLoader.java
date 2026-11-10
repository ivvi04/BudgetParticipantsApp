package ru.lakeevda.budgetparticipantsapp.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Лоадер конфигурации.
 * Загружает свойства из application.properties, расположенного в classpath.
 * Обеспечивает кэширование и единый экземпляр конфигурации.
 *
 * @author lakeev-da
 * @since 2026-11-06
 */
public class ConfigLoader {

    private static final Logger log = Logger.getLogger(ConfigLoader.class.getName());
    private static final ConfigLoader INSTANCE = new ConfigLoader();

    private static final String PROPERTIES_FILE = "application.properties";

    private final Properties properties;

    /**
     * Конструктор-фабрика.
     * Загружает свойства из application.properties.
     * @throws IllegalStateException если файл конфигурации не найден
     * @throws RuntimeException при ошибке загрузки конфигурации
     */
    private ConfigLoader() {
        this.properties = new Properties();
        try (InputStream is = ConfigLoader.class.getClassLoader().getResourceAsStream(PROPERTIES_FILE)) {
            if (is == null) {
                throw new IllegalStateException("Файл свойств '" + PROPERTIES_FILE + "' не найден в classpath");
            }
            this.properties.load(is);
            log.info("Конфигурация загружена из " + PROPERTIES_FILE);
        } catch (IOException e) {
            String message = "Не удалось загрузить свойства из '" + PROPERTIES_FILE + "'";
            log.log(Level.SEVERE, message, e);
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


    /**
     * Получает значение свойства с заданным значением по умолчанию.
     *
     * @param key ключ свойства
     * @param defaultValue значение по умолчанию
     * @return значение свойства или defaultValue если ключ не найден
     */
    public static String getProperty(String key, String defaultValue) {
        String value;
        try {
            value = getProperty(key);
        } catch (Exception e) {
            value = defaultValue;
        }
        return value;
    }

    /**
     * Получает значение свойства.
     *
     * @param key ключ свойства
     * @return значение свойства
     * @throws RuntimeException если ключ не найден
     */
    public static String getProperty(String key) {
        if (INSTANCE.properties.containsKey(key)) {
            return INSTANCE.properties.getProperty(key);
        } else {
            String message = "Свойство с ключом '" + key + "' не найдено";
            log.log(Level.INFO, message);
            throw new RuntimeException(message);
        }
    }

}
