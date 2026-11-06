package ru.lakeevda.budgetparticipantsapp.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Тесты для {@link JsonToXmlConverter}.
 *
 * @author lakeev-da
 * @since 2026-11-06
 */
public class JsonToXmlConverterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Проверяет преобразование объекта с вложенностью и спецсимволами.
     *
     * @throws Exception при ошибках парсинга
     */
    @Test
    public void convertsObjectWithNestedFields() throws Exception {
        JsonNode node = mapper.readTree("{\"id\":\"1\","
                + "\"info\":{\"fullName\":\"УПРАВЛЕНИЕ & КО\"}}");
        Document doc = parse(JsonToXmlConverter.toXml(node));
        Element root = doc.getDocumentElement();
        assertEquals("page", root.getTagName());

        Element item = firstChild(root, "item");
        assertEquals("1", childText(item, "id"));
        Element info = firstChild(item, "info");
        assertEquals("УПРАВЛЕНИЕ & КО", childText(info, "fullName"));
    }

    /**
     * Проверяет, что массив в поле превращается в набор элементов item.
     *
     * @throws Exception при ошибках парсинга
     */
    @Test
    public void convertsArrayFieldToItemElements() throws Exception {
        JsonNode node = mapper.readTree("{\"ids\":[1,2,3]}");
        Document doc = parse(JsonToXmlConverter.toXml(node));

        Element item = firstChild(doc.getDocumentElement(), "item");
        Element ids = firstChild(item, "ids");
        NodeList items = ids.getElementsByTagName("item");
        assertEquals(3, items.getLength());
        assertEquals("1", items.item(0).getTextContent());
        assertEquals("3", items.item(2).getTextContent());
    }

    /**
     * Проверяет, что корневой массив даёт несколько элементов item.
     *
     * @throws Exception при ошибках парсинга
     */
    @Test
    public void convertsRootArrayToMultipleItems() throws Exception {
        JsonNode node = mapper.readTree("[{\"id\":1},{\"id\":2}]");
        Document doc = parse(JsonToXmlConverter.toXml(node));

        NodeList items = doc.getDocumentElement().getElementsByTagName("item");
        assertEquals(2, items.getLength());
        assertEquals("1", childText((Element) items.item(0), "id"));
        assertEquals("2", childText((Element) items.item(1), "id"));
    }

    /**
     * Проверяет санитизацию недопустимых имён XML-тегов.
     *
     * @throws Exception при ошибках парсинга
     */
    @Test
    public void sanitizesInvalidTagNames() throws Exception {
        JsonNode node = mapper.readTree("{\"first name\":\"a\","
                + "\"a:b\":\"b\",\"1abc\":\"c\"}");
        Document doc = parse(JsonToXmlConverter.toXml(node));

        Element item = firstChild(doc.getDocumentElement(), "item");
        assertEquals("a", childText(item, "first_name"));
        assertEquals("b", childText(item, "a_b"));
        assertEquals("c", childText(item, "f_1abc"));
    }

    /**
     * Проверяет, что null-значение остаётся пустым элементом.
     *
     * @throws Exception при ошибках парсинга
     */
    @Test
    public void keepsNullFieldAsEmptyElement() throws Exception {
        JsonNode node = mapper.readTree("{\"empty\":null}");
        Document doc = parse(JsonToXmlConverter.toXml(node));

        Element item = firstChild(doc.getDocumentElement(), "item");
        Element empty = firstChild(item, "empty");
        assertEquals("", empty.getTextContent());
    }

    /**
     * Проверяет наличие XML-декларации.
     *
     * @throws Exception при ошибках парсинга
     */
    @Test
    public void producesXmlDeclaration() throws Exception {
        String xml = JsonToXmlConverter.toXml(mapper.readTree("{\"id\":1}"));
        assertTrue(xml.contains("<?xml"));
    }

    private Document parse(String xml) throws Exception {
        return DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new InputSource(new StringReader(xml)));
    }

    private static Element firstChild(Element parent, String name) {
        NodeList nodes = parent.getElementsByTagName(name);
        assertTrue("No element " + name, nodes.getLength() > 0);
        return (Element) nodes.item(0);
    }

    private static String childText(Element parent, String name) {
        return firstChild(parent, name).getTextContent();
    }
}
