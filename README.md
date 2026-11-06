# data-loader

Консольное Java-приложение (Java 8, Maven): скачивает реестр УБП/НУБП из ЕПБС
(https://budget.gov.ru/epbs/registry/ubpandnubp/data) за период
`lastUpdateFrom..lastUpdateTo`, раскладывает записи в денормализованную таблицу
PostgreSQL, при необходимости переносит её в ClickHouse и архивирует ранее
скачанные ответы (JSON → XML в zip).

## Структура

    src/main/java/ru/lakeevda/budgetparticipantsapp/
      BudgetParticipantsApp.java      точка входа, оркестрация конвейера
      client/ApiClient.java           пагинация и скачивание страниц API
      config/ConfigLoader.java        загрузка application.properties из classpath
      db/                             DatabaseConnection, PostgresConnection,
                                      ClickHouseConnection
      mapper/JsonFlattener.java       JSON → плоская карта колонок
      mapper/JsonToXmlConverter.java  JSON → XML для архива
      service/DataLoadService.java    запись в PostgreSQL, динамические колонки
      service/ClickHouseTransferService.java  перенос PG → ClickHouse
      service/ZipService.java         архивация JSON в zip (XML внутри)
    src/main/resources/application.properties
    src/test/java/...                 JUnit 4 тесты

## Развёртывание БД

    docker compose up -d

Поднимаются:

- PostgreSQL 13 (внутри контейнера 5432, наружу проброшен **5433**) —
  образ собирается с блоками 32 КБ;
- ClickHouse 22.3 (наружу 8123 для JDBC/HTTP и 9000 для native-клиентов).

## Конфигурация

Все параметры — в `src/main/resources/application.properties` (читаются из
classpath, файл вне jar не поддерживается):

| Ключ | Назначение |
|------|------------|
| `api.url` | шаблон URL; `%1$s` = from, `%2$s` = to, `%3$s` = pageNum |
| `api.dateFormat` | формат дат в URL (`dd.MM.yyyy`) |
| `api.itemsKey` | поле с массивом записей (`data`) |
| `api.totalPagesKey` | поле с числом страниц (`pageCount`) |
| `postgres.url` / `username` / `password` | подключение к PostgreSQL (порт 5433) |
| `postgres.table` | имя денормализованной таблицы |
| `storage.rawDir` | каталог для скачанных JSON и zip (`data/raw`) |
| `clickhouse.enabled` | включён ли перенос в ClickHouse |
| `clickhouse.url` / `user` / `password` | подключение к ClickHouse |
| `clickhouse.table` | имя таблицы в ClickHouse |

## Сборка и запуск

    mvn clean package
    java -jar target/data-loader-1.0.0-jar-with-dependencies.jar 2024-01-01 2024-01-31

Обязательные аргументы: `lastUpdateFrom` и `lastUpdateTo` в формате ISO
(`yyyy-MM-dd`), иначе приложение завершается с ошибкой. Если `from > to`,
запуск прерывается. В URL сервиса даты подставляются в формате `dd.MM.yyyy`
согласно `api.dateFormat`.

## Конвейер

Порядок шагов в `BudgetParticipantsApp.run`:

1. **Архивация прошлых запусков** — все `*.json` из `storage.rawDir`
   конвертируются в XML и складываются в `archive_<from>_<to>.zip`, после чего
   исходные JSON удаляются. Некорректный JSON не удаляется и в архив не попадает.
2. **Скачивание** — пагинация `pageSize=500` (зашито в шаблон URL), `pageNum`
   с 1. Остановка по достижении `pageCount` или (если счётчик неизвестен) по
   пустому массиву `data`. Сырые ответы пишутся в
   `page_<from>_<to>_<pageNum>.json`.
3. **Загрузка в PostgreSQL** — таблица создаётся при отсутствии. Колонки
   добавляются по мере появления новых полей (`ALTER TABLE ADD COLUMN ... text`).
   Повторная загрузка за тот же период перезаписывает данные:
   `DELETE ... WHERE last_update_from=? AND last_update_to=?` + `INSERT` в одной
   транзакции.
4. **Перенос в ClickHouse** — если `clickhouse.enabled=true`: таблица создаётся
   со всеми колонками типа `String`, `ENGINE = MergeTree ORDER BY id`; строки
   копируются батчами по 5000 (чтение из PG — fetch size 1000).
5. **Логирование итога**.

## Денормализация

- Вложенные объекты разворачиваются через `_`: `info.fullName` → `info_fullname`.
- Массивы не хранятся как json/jsonb: элементы получают индекс в имени колонки —
  `heads_0_fio`, `facialaccounts_1_num`, `activities_0_activitycode`.
- `null`, пустые объекты и пустые массивы пропускаются.
- Имена колонок приводятся к нижнему регистру; недопустимые символы заменяются
  на `_`.
- В каждой записи дополнительно проставляются `last_update_from` и
  `last_update_to`.
- Вставка группирует строки по набору непустых колонок, чтобы не передавать
  NULL-параметры; размер батча = `32000 / число колонок группы`.

## Лимит колонок PostgreSQL

PostgreSQL допускает до 1600 колонок. При приближении к 1500 приложение пишет
WARN, после 1595 новые колонки не добавляются, а значения таких тегов
пропускаются. Таблица создаётся с первичным ключом `id text`.

## Тесты

    mvn test

Покрыты: `ApiClient` (извлечение массива), `JsonFlattener` (разворачивание,
санитизация), `JsonToXmlConverter` (XML-представление), `ZipService`
(архивация/удаление/невалидный JSON), `ClickHouseTransferService` (флаг
включения).

## Известные проблемы

- `ClickHouseConnection` читает ключ `clickhouse.username`, а в
  `application.properties` он называется `clickhouse.user`; перенос в
  ClickHouse из-за этого падает при инициализации.
- `BudgetParticipantsApp` использует несуществующий ключ `db.table` вместо
  `postgres.table` (и при переносе, и в итоговом логе).
- `ZipService` дописывает архив (`FileOutputStream(..., true)`): при повторной
  архивации за тот же период в zip могут появиться дубликаты записей.
