package javaos.office;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Reading and writing the XML parts. Reading goes through the JDK's DOM parser
 * with entity expansion switched off, because office files arrive from
 * elsewhere and a document that quietly resolves external entities is a way to
 * read files it was never handed.
 */
public final class Xml {

    // ODF
    public static final String OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0";
    public static final String TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0";
    public static final String STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0";
    public static final String FO =
            "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0";
    public static final String TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0";
    public static final String MANIFEST =
            "urn:oasis:names:tc:opendocument:xmlns:manifest:1.0";
    /** The formula namespace. A table:formula whose of: prefix is undeclared is junk. */
    public static final String OF = "urn:oasis:names:tc:opendocument:xmlns:of:1.2";

    /** Where ODF keeps the font declarations {@code style:font-name} points at. */
    public static final String SVG =
            "urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0";

    // OOXML
    public static final String W =
            "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    public static final String SHEET =
            "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    public static final String REL =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private Xml() {
    }

    // ---- reading -------------------------------------------------------

    public static Document parse(byte[] content) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setExpandEntityReferences(false);
            factory.setXIncludeAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) ->
                    new org.xml.sax.InputSource(new java.io.StringReader("")));
            return builder.parse(new ByteArrayInputStream(content));
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("malformed XML: " + e.getMessage(), e);
        }
    }

    /** Direct element children, in document order. */
    public static List<Element> children(Node parent) {
        List<Element> elements = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element) {
                elements.add(element);
            }
        }
        return elements;
    }

    /** Every descendant with this namespace and local name, in document order. */
    public static List<Element> descendants(Node root, String namespace, String localName) {
        List<Element> found = new ArrayList<>();
        collect(root, namespace, localName, found);
        return found;
    }

    private static void collect(Node node, String namespace, String localName,
            List<Element> found) {
        for (Element child : children(node)) {
            if (matches(child, namespace, localName)) {
                found.add(child);
            }
            collect(child, namespace, localName, found);
        }
    }

    public static Element first(Node parent, String namespace, String localName) {
        for (Element child : children(parent)) {
            if (matches(child, namespace, localName)) {
                return child;
            }
        }
        return null;
    }

    /** The first descendant at any depth, for parts whose nesting varies. */
    public static Element firstDeep(Node root, String namespace, String localName) {
        List<Element> found = descendants(root, namespace, localName);
        return found.isEmpty() ? null : found.get(0);
    }

    public static boolean matches(Element element, String namespace, String localName) {
        return localName.equals(element.getLocalName())
                && (namespace == null || namespace.equals(element.getNamespaceURI()));
    }

    public static String attr(Element element, String namespace, String localName) {
        String value = element.getAttributeNS(namespace, localName);
        if (value == null || value.isEmpty()) {
            // Some producers write attributes without a namespace at all.
            value = element.getAttribute(localName);
        }
        return value == null || value.isEmpty() ? null : value;
    }

    public static String attr(Element element, String namespace, String localName,
            String fallback) {
        String value = attr(element, namespace, localName);
        return value == null ? fallback : value;
    }

    public static int intAttr(Element element, String namespace, String localName, int fallback) {
        String value = attr(element, namespace, localName);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** All text below this element, ignoring markup. */
    public static String textOf(Node node) {
        StringBuilder text = new StringBuilder();
        NodeList nodes = node.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node child = nodes.item(i);
            if (child.getNodeType() == Node.TEXT_NODE
                    || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                text.append(child.getNodeValue());
            } else if (child.getNodeType() == Node.ELEMENT_NODE) {
                text.append(textOf(child));
            }
        }
        return text.toString();
    }

    // ---- writing -------------------------------------------------------

    /**
     * A small streaming writer. Generating these parts by hand beats building a
     * DOM and serialising it: the output is predictable, and the namespace
     * prefixes stay the ones every office reader expects to see.
     */
    public static final class Builder {
        private final StringBuilder out = new StringBuilder(4096);
        private final ArrayList<String> open = new ArrayList<>();
        private boolean elementOpen;

        public Builder() {
            out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        }

        public Builder start(String name) {
            closeStartTag();
            out.append('<').append(name);
            open.add(name);
            elementOpen = true;
            return this;
        }

        public Builder attr(String name, String value) {
            if (!elementOpen) {
                throw new IllegalStateException("no element is open for " + name);
            }
            if (value != null) {
                out.append(' ').append(name).append("=\"").append(escape(value, true))
                        .append('"');
            }
            return this;
        }

        public Builder text(String value) {
            closeStartTag();
            out.append(escape(value, false));
            return this;
        }

        /** Writes markup verbatim; callers are responsible for its correctness. */
        public Builder raw(String markup) {
            closeStartTag();
            out.append(markup);
            return this;
        }

        public Builder end() {
            if (open.isEmpty()) {
                throw new IllegalStateException("no element to close");
            }
            String name = open.remove(open.size() - 1);
            if (elementOpen) {
                out.append("/>");
                elementOpen = false;
            } else {
                out.append("</").append(name).append('>');
            }
            return this;
        }

        /** Opens an element, writes its text, and closes it. */
        public Builder element(String name, String text) {
            return start(name).text(text).end();
        }

        private void closeStartTag() {
            if (elementOpen) {
                out.append('>');
                elementOpen = false;
            }
        }

        public String done() {
            while (!open.isEmpty()) {
                end();
            }
            closeStartTag();
            return out.toString();
        }

        public byte[] bytes() {
            return Zip.bytes(done());
        }
    }

    /** XML escaping. Attributes additionally escape quotes and newlines. */
    public static String escape(String value, boolean attribute) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append(attribute ? "&quot;" : "\"");
                case '\'' -> escaped.append(attribute ? "&apos;" : "'");
                case '\r' -> escaped.append(attribute ? "&#13;" : "");
                case '\n' -> escaped.append(attribute ? "&#10;" : "\n");
                case '\t' -> escaped.append(attribute ? "&#9;" : "\t");
                default -> {
                    // XML 1.0 forbids most control characters outright.
                    if (ch >= 0x20 || ch == '\n' || ch == '\t') {
                        escaped.append(ch);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
