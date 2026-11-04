# data-loader

Консольное приложение: скачивает реестр УБП/НУБП из ЕПБС
(https://budget.gov.ru/epbs/registry/ubpandnubp/data) за период
lastUpdateFrom..lastUpdateTo, раскладывает в денормализованную таблицу
PostgreSQL, переносит её в ClickHouse и архивирует скачанные ответы
(json + xml) в zip.

## Развёртывание БД

    docker compose up -d

Поднимаются PostgreSQL 13 (порт 5432) и ClickHouse 22.3 (порт 8123 для JDBC).

## Сборка и запуск

    mvn clean package
    java -jar target/data-loader-1.0.0-jar-with-dependencies.jar 2024-01-01 2024-01-31

Даты в аргументах — ISO (yyyy-MM-dd); в URL сервиса подставляются
в формате dd.MM.yyyy (api.dateFormat).

## Конвейер

1. Пагинация: pageSize=500, pageNum с 1, остановка по pageCount
   или по пустому массиву data. Сырые ответы — в storage.rawDir.
2. Денормализация: каждый тег JSON — отдельная колонка text. Массивы не
   пишутся как json/jsonb: элементы получают колонки с индексами
   (heads_0_fio, facialaccounts_1_num). Схема расширяется автоматически
   (ALTER TABLE ADD COLUMN).
3. Перезапуск за тот же период перезаписывает данные по ключам
   last_update_from / last_update_to (DELETE + INSERT в одной транзакции).
4. Файлы прошлых загрузок упаковываются в archive_<from>_<to>.zip вместе
   с производными xml-копиями; оригиналы json удаляются.
5. Содержимое таблицы PostgreSQL переносится в ClickHouse (батчи по 5000).

## Лимит колонок

Postgres держит до 1600 колонок. При подходе к 1500 приложение пишет WARN,
после 1595 перестаёт добавлять колонки (данные таких тегов пропускаются).

## Тесты

    mvn test
