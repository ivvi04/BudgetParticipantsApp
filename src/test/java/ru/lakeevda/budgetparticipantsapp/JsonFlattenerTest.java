package ru.lakeevda.budgetparticipantsapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import ru.lakeevda.budgetparticipantsapp.mapper.JsonFlattener;

import java.util.Map;

import static org.junit.Assert.*;

/**
 * Тесты для класса JsonFlattener.
 * Проверяет корректность разворачивания JSON в плоскую структуру.
 *
 * @author lakeev-da
 * @since 2026-11-06
 */
public class JsonFlattenerTest {

    /**
     * Проверяет разворачивание структуры записи УПФР Болотнинского района.
     *
     * @throws Exception при ошибках парсинга JSON
     */
    @Test
    public void flattensRubpEntry() throws Exception {
        // Структура - по фактическому ответу ЕПБС (запись УПФР Болотнинского района)
        String json = "{"
                + "\"id\": \"3269685\","
                + "\"info\": {\"regNum\": \"П2264\", \"fullName\": \"УПРАВЛЕНИЕ ПФР\","
                + "  \"inn\": \"5413107604\", \"isOGV\": \"1\", \"egrulnotincluded\": \"false\","
                + "  \"dateUpdate\": null},"
                + "\"authorities\": [],"
                + "\"activities\": [{\"activityCode\": \"84.30\","
                + "  \"activityName\": \"Деятельность в области обязательного социального обеспечения\","
                + "  \"activityKind\": \"основной\"}],"
                + "\"heads\": [{\"fio\": \"ПУРИС ЕЛЕНА ПЕТРОВНА\", \"post\": \"НАЧАЛЬНИК\","
                + "  \"docName\": \"приказ\", \"docNum\": \"428-л\","
                + "  \"docDate\": \"2016-12-20 00:00:00.0\", \"headMain\": \"false\"}],"
                + "\"facialAccounts\": ["
                + "  {\"kindName\": \"лс ПБС\", \"kindCode\": \"03\", \"num\": \"03514П22640\","
                + "   \"openUfkCode\": \"5100\"},"
                + "  {\"kindName\": \"лс для учета операций со СВР\", \"kindCode\": \"05\","
                + "   \"num\": \"05514П22640\", \"openUfkCode\": \"5100\"}"
                + "],"
                + "\"contracts\": []"
                + "}";
        Map<String, String> flat = JsonFlattener.flatten(new ObjectMapper().readTree(json));

        assertEquals("3269685", flat.get("id"));
        assertEquals("П2264", flat.get("info_regnum"));
        assertEquals("УПРАВЛЕНИЕ ПФР", flat.get("info_fullname"));
        assertEquals("5413107604", flat.get("info_inn"));
        assertEquals("1", flat.get("info_isogv"));
        assertEquals("false", flat.get("info_egrulnotincluded"));
        assertNull(flat.get("info_dateupdate"));
        assertFalse(flat.containsKey("authorities")); // пустой массив не пишется
        assertEquals("84.30", flat.get("activities_0_activitycode"));
        assertEquals("основной", flat.get("activities_0_activitykind"));
        assertEquals("ПУРИС ЕЛЕНА ПЕТРОВНА", flat.get("heads_0_fio"));
        assertEquals("false", flat.get("heads_0_headmain"));
        assertEquals("лс ПБС", flat.get("facialaccounts_0_kindname"));
        assertEquals("05514П22640", flat.get("facialaccounts_1_num"));
        assertFalse(flat.containsKey("contracts"));
    }

    /**
     * Проверяет санитизацию имён колонок (преобразование в нижний регистр).
     *
     * @throws Exception при ошибках парсинга JSON
     */
    @Test
    public void sanitizesColumnNames() throws Exception {
        String json = "{\"firstName-A\": 1}";
        Map<String, String> flat = JsonFlattener.flatten(new ObjectMapper().readTree(json));
        assertEquals("1", flat.get("firstname_a"));
    }

    /**
     * Проверяет, что массивы не сохраняются целиком как ключи.
     * Регрессионная проверка.
     *
     * @throws Exception при ошибках парсинга JSON
     */
    @Test
    public void noWholeArraysStored() throws Exception {
        // Регрессионная проверка: ни один ключ не представляет массив целиком
        String json = "{\"heads\": [{\"fio\": \"Иванов И.И.\"}, {\"fio\": \"Петров П.П.\"}]}";
        Map<String, String> flat = JsonFlattener.flatten(new ObjectMapper().readTree(json));
        for (String key : flat.keySet()) {
            assertFalse(key.equals("heads"));
            assertFalse(key.contains("["));
        }
        assertEquals("Иванов И.И.", flat.get("heads_0_fio"));
        assertEquals("Петров П.П.", flat.get("heads_1_fio"));
    }
}

