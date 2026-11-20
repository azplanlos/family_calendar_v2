package de.goForFun.familienkalender.outlook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Inspiziert die pom.xml: SSM ohne eigene Version (BOM), Jackson 3, keine MSAL,
 * exec-maven-plugin-Execution und Shade-Filter für das Bootstrap-Programm.
 *
 * Validates: Requirements 8.1, 8.17, 11.12, 11.13
 */
class PomInspectionTest {

    private static Document pom;

    @BeforeAll
    static void parsePom() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        pom = factory.newDocumentBuilder().parse(new File("pom.xml"));
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n instanceof Element e && e.getTagName().equals(name)) {
                result.add(e);
            }
        }
        return result;
    }

    private static String text(Element parent, String name) {
        List<Element> list = children(parent, name);
        return list.isEmpty() ? null : list.get(0).getTextContent().trim();
    }

    private static Element root() {
        return pom.getDocumentElement();
    }

    private static List<Element> directDependencies() {
        List<Element> result = new ArrayList<>();
        for (Element deps : children(root(), "dependencies")) {
            result.addAll(children(deps, "dependency"));
        }
        return result;
    }

    private static Element findDependency(String groupId, String artifactId) {
        return directDependencies().stream()
                .filter(d -> groupId.equals(text(d, "groupId")) && artifactId.equals(text(d, "artifactId")))
                .findFirst()
                .orElse(null);
    }

    private static Element findPlugin(String artifactId) {
        for (Element build : children(root(), "build")) {
            for (Element plugins : children(build, "plugins")) {
                for (Element plugin : children(plugins, "plugin")) {
                    if (artifactId.equals(text(plugin, "artifactId"))) {
                        return plugin;
                    }
                }
            }
        }
        return null;
    }

    @Test
    void ssmDependencyHasNoVersion() {
        Element ssm = findDependency("software.amazon.awssdk", "ssm");
        assertThat(ssm).as("ssm-Dependency").isNotNull();
        assertThat(children(ssm, "version")).as("ssm <version> (kommt aus dem AWS-SDK-BOM)").isEmpty();
    }

    @Test
    void noMsalDependency() {
        for (Element dep : directDependencies()) {
            assertThat(text(dep, "groupId")).doesNotContainIgnoringCase("msal");
            assertThat(text(dep, "artifactId")).doesNotContainIgnoringCase("msal");
            assertThat(text(dep, "groupId")).isNotEqualTo("com.microsoft.azure");
        }
        assertThat(pom.getDocumentElement().getTextContent().toLowerCase()).doesNotContain("msal");
    }

    @Test
    void jackson3DatabindPinnedViaProperty() {
        Element jackson = findDependency("tools.jackson.core", "jackson-databind");
        assertThat(jackson).as("Jackson-3-Dependency").isNotNull();
        assertThat(text(jackson, "version")).isEqualTo("${tools.jackson.version}");
        assertThat(findDependency("com.fasterxml.jackson.core", "jackson-databind")).isNull();
        Element props = children(root(), "properties").get(0);
        assertThat(text(props, "tools.jackson.version")).matches("3\\.\\d+\\.\\d+");
    }

    @Test
    void execPluginHasGraphBootstrapExecution() {
        Element plugin = findPlugin("exec-maven-plugin");
        assertThat(plugin).as("exec-maven-plugin").isNotNull();
        Element execution = null;
        for (Element executions : children(plugin, "executions")) {
            for (Element e : children(executions, "execution")) {
                if ("graph-bootstrap".equals(text(e, "id"))) {
                    execution = e;
                }
            }
        }
        assertThat(execution).as("Execution graph-bootstrap").isNotNull();
        Element config = children(execution, "configuration").get(0);
        assertThat(text(config, "mainClass"))
                .isEqualTo("de.goForFun.familienkalender.outlook.bootstrap.GraphBootstrap");
    }

    @Test
    void shadeFilterExcludesBootstrapPackage() {
        Element shade = findPlugin("maven-shade-plugin");
        assertThat(shade).as("maven-shade-plugin").isNotNull();
        List<String> excludes = new ArrayList<>();
        for (Element config : children(shade, "configuration")) {
            for (Element filters : children(config, "filters")) {
                for (Element filter : children(filters, "filter")) {
                    for (Element ex : children(filter, "excludes")) {
                        for (Element e : children(ex, "exclude")) {
                            excludes.add(e.getTextContent().trim());
                        }
                    }
                }
            }
        }
        assertThat(excludes).contains("de/goForFun/familienkalender/outlook/bootstrap/**");
    }
}
