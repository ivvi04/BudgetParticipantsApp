package ru.lakeevda.budgetparticipantsapp.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

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
     * Проверяет, что индексы элементов массива не зависят от порядка
     * элементов в ответе API.
     *
     * @throws Exception при ошибках парсинга JSON
     */
    @Test
    public void assignsArrayIndicesRegardlessOfSourceOrder() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String direct = "{\"heads\":[{\"fio\":\"Петров П.П.\",\"post\":\"Б\"},"
                + "{\"fio\":\"Иванов И.И.\",\"post\":\"А\"}]}";
        String reversed = "{\"heads\":[{\"fio\":\"Иванов И.И.\",\"post\":\"А\"},"
                + "{\"fio\":\"Петров П.П.\",\"post\":\"Б\"}]}";

        Map<String, String> first = JsonFlattener.flatten(mapper.readTree(direct));
        Map<String, String> second = JsonFlattener.flatten(mapper.readTree(reversed));

        assertEquals(first, second);
        assertEquals("Иванов И.И.", first.get("heads_0_fio"));
        assertEquals("А", first.get("heads_0_post"));
        assertEquals("Петров П.П.", first.get("heads_1_fio"));
        assertEquals("Б", first.get("heads_1_post"));
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

    /**
     * Проверяет разворачивание вложенных массивов (массив массивов).
     *
     * @throws Exception при ошибках парсинга JSON
     */
    @Test
    public void flattensNestedArrays() throws Exception {
        Map<String, String> flat = JsonFlattener.flatten(
                new ObjectMapper().readTree("{\"m\":[[1,2],[3]]}"));

        assertEquals("1", flat.get("m_0_0"));
        assertEquals("2", flat.get("m_0_1"));
        assertEquals("3", flat.get("m_1_0"));
    }

    /**
     * Проверяет, что пустые объекты, пустые массивы и null пропускаются.
     *
     * @throws Exception при ошибках парсинга JSON
     */
    @Test
    public void skipsEmptyContainersAndNulls() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        assertTrue(JsonFlattener.flatten(mapper.readTree("{}")).isEmpty());
        assertTrue(JsonFlattener.flatten(mapper.readTree("[]")).isEmpty());
        assertTrue(JsonFlattener.flatten(
                mapper.readTree("{\"a\":{},\"b\":[],\"c\":null}")).isEmpty());
        assertTrue(JsonFlattener.flatten(mapper.readTree("{\"a\":[null]}")).isEmpty());
    }
}

