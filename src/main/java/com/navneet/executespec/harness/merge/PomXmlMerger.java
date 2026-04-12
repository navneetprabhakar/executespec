package com.navneet.executespec.harness.merge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Deterministic merger for Maven pom.xml files.
 *
 * <p>Strategy: union dependencies and plugins from both sides, deduplicate
 * by groupId:artifactId. If the same GA pair has different versions,
 * falls through to the LLM resolver (returns empty).
 */
@Component
@Order(1)
@Slf4j
public class PomXmlMerger implements SpecializedMerger {

    @Override
    public boolean canHandle(String filePath) {
        return filePath.endsWith("pom.xml");
    }

    @Override
    public Optional<String> merge(String base, String ours, String theirs) {
        try {
            Document baseDoc = parseXml(base);
            Document oursDoc = parseXml(ours);
            Document theirsDoc = parseXml(theirs);

            // Merge dependencies
            boolean depsOk = mergeSection(oursDoc, theirsDoc, baseDoc, "dependencies", "dependency");
            if (!depsOk) return Optional.empty();

            // Merge plugins (inside build/plugins)
            mergeBuildPlugins(oursDoc, theirsDoc, baseDoc);

            return Optional.of(serializeXml(oursDoc));
        } catch (Exception e) {
            log.debug("PomXmlMerger failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Merges a section (e.g., dependencies) from theirs into ours.
     * Returns false if version conflicts are detected.
     */
    private boolean mergeSection(Document ours, Document theirs, Document base,
                                  String parentTag, String childTag) {
        Map<String, Element> ourItems = extractItems(ours, parentTag, childTag);
        Map<String, Element> theirItems = extractItems(theirs, parentTag, childTag);
        Map<String, Element> baseItems = extractItems(base, parentTag, childTag);

        // Find items added by theirs that aren't in ours
        for (Map.Entry<String, Element> entry : theirItems.entrySet()) {
            String key = entry.getKey();
            if (!ourItems.containsKey(key)) {
                // New item from theirs — add to ours
                addItemToSection(ours, parentTag, entry.getValue());
            } else if (!baseItems.containsKey(key)) {
                // Both sides added the same GA — check version match
                String ourVersion = getChildText(ourItems.get(key), "version");
                String theirVersion = getChildText(entry.getValue(), "version");
                if (ourVersion != null && theirVersion != null && !ourVersion.equals(theirVersion)) {
                    log.warn("Version conflict for {}: ours={} theirs={}", key, ourVersion, theirVersion);
                    return false; // can't auto-resolve version conflicts
                }
            }
        }
        return true;
    }

    private void mergeBuildPlugins(Document ours, Document theirs, Document base) {
        // Simplified: merge top-level plugins section
        mergeSection(ours, theirs, base, "plugins", "plugin");
    }

    private Map<String, Element> extractItems(Document doc, String parentTag, String childTag) {
        Map<String, Element> items = new LinkedHashMap<>();
        NodeList parents = doc.getElementsByTagName(parentTag);
        for (int i = 0; i < parents.getLength(); i++) {
            NodeList children = ((Element) parents.item(i)).getElementsByTagName(childTag);
            for (int j = 0; j < children.getLength(); j++) {
                Element el = (Element) children.item(j);
                String key = buildKey(el);
                if (key != null) items.put(key, el);
            }
        }
        return items;
    }

    private String buildKey(Element el) {
        String groupId = getChildText(el, "groupId");
        String artifactId = getChildText(el, "artifactId");
        if (groupId == null || artifactId == null) return null;
        return groupId + ":" + artifactId;
    }

    private String getChildText(Element el, String tag) {
        NodeList list = el.getElementsByTagName(tag);
        if (list.getLength() == 0) return null;
        return list.item(0).getTextContent().trim();
    }

    private void addItemToSection(Document doc, String parentTag, Element item) {
        NodeList parents = doc.getElementsByTagName(parentTag);
        if (parents.getLength() > 0) {
            Node imported = doc.importNode(item, true);
            parents.item(0).appendChild(imported);
        }
    }

    private Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    private String serializeXml(Document doc) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
        StringWriter sw = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(sw));
        return sw.toString();
    }
}
