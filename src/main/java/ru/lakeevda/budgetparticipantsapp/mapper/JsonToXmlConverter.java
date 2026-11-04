package ru.lakeevda.budgetparticipantsapp.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;
import java.util.Iterator;
import java.util.Map.Entry;

/**
 * Конвертация JSON в XML для помещения в архив.
 */
public final class JsonToXmlConverter {

    private JsonToXmlConverter() {
    }

    public static String toXml(JsonNode node) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        Document doc = dbf.newDocumentBuilder().newDocument();
        Element root = doc.createElement("page");
        doc.appendChild(root);
        if (node.isArray()) {
            for (JsonNode item : node) {
                root.appendChild(buildElement(doc, "item", item));
            }
        } else {
            root.appendChild(buildElement(doc, "item", node));
        }

        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer t = tf.newTransformer();
        t.setOutputProperty(OutputKeys.INDENT, "yes");
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        StringWriter sw = new StringWriter();
        t.transform(new DOMSource(doc), new StreamResult(sw));
        return sw.toString();
    }

    private static Element buildElement(Document doc,
                                        String name, JsonNode node) {
        Element el = doc.createElement(sanitize(name));
        if (node.isObject()) {
            Iterator<Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Entry<String, JsonNode> e = fields.next();
                el.appendChild(buildElement(doc, e.getKey(), e.getValue()));
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                el.appendChild(buildElement(doc, "item", item));
            }
        } else if (!node.isNull()) {
            el.setTextContent(node.asText());
        }
        return el;
    }

    /**
     * XML-теги не принимают произвольные символы — приводим к валидному виду.
     */
    private static String sanitize(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) || c == '_' || c == '-' ? c : '_');
        }
        if (sb.length() == 0 || Character.isDigit(sb.charAt(0))) sb.insert(0, "f_");
        return sb.toString();
    }
}

