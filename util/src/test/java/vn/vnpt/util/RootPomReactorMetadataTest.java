package vn.vnpt.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Regression guard for the monorepo reactor invariants introduced in Story 0.2.
 *
 * <p>The story is pure pom.xml + directory scaffolding; behavior is unchanged, but the
 * architectural commitments (groupId, packaging, module count, no root-BOM re-import) would
 * silently rot without an automated check. Each test here mirrors one AC and fails loudly if the
 * invariant breaks, replacing the manual {@code mvn validate} dance with something CI can run.
 */
class RootPomReactorMetadataTest {

  private static final Path ROOT_POM = Paths.get("..", "pom.xml").toAbsolutePath().normalize();
  private static final String EXPECTED_GROUP_ID = "vn.vnpt";
  private static final String EXPECTED_PACKAGING = "pom";
  private static final int EXPECTED_MODULE_COUNT = 17; // util + 14 services + 2 BFFs

  private static Document loadRootPom() throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    DocumentBuilder builder = factory.newDocumentBuilder();
    Document doc = builder.parse(ROOT_POM.toFile());
    doc.getDocumentElement().normalize();
    return doc;
  }

  private static Element project(Document doc) {
    Element project = doc.getDocumentElement();
    assertEquals("project", project.getNodeName(), "root element must be <project>");
    return project;
  }

  private static String childText(Element parent, String name) {
    NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node n = children.item(i);
      if (n.getNodeType() == Node.ELEMENT_NODE && name.equals(n.getNodeName())) {
        return n.getTextContent().trim();
      }
    }
    return null;
  }

  private static Element modulesElement(Document doc) {
    Element project = project(doc);
    NodeList children = project.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node n = children.item(i);
      if (n.getNodeType() == Node.ELEMENT_NODE && "modules".equals(n.getNodeName())) {
        return (Element) n;
      }
    }
    return null;
  }

  @Test
  void ac8_rootGroupIdIsVnVnpt() throws Exception {
    Element project = project(loadRootPom());
    assertEquals(
        EXPECTED_GROUP_ID,
        childText(project, "groupId"),
        "AC #8: root <groupId> must be "
            + EXPECTED_GROUP_ID
            + " per CONVENTIONS.md §2 (Java modules)");
  }

  @Test
  void ac7_rootPackagingIsPom() throws Exception {
    Element project = project(loadRootPom());
    assertEquals(
        EXPECTED_PACKAGING,
        childText(project, "packaging"),
        "AC #7: root pom must declare <packaging>pom</packaging> as the reactor parent");
  }

  @Test
  void ac7_modulesCountIs17AndAllPathsExist() throws Exception {
    Document doc = loadRootPom();
    Element modules = modulesElement(doc);
    assertNotNull(modules, "AC #7: root pom must declare a <modules> element");

    NodeList moduleNodes = modules.getElementsByTagName("module");
    assertEquals(
        EXPECTED_MODULE_COUNT,
        moduleNodes.getLength(),
        "AC #3+#4+#7: expected "
            + EXPECTED_MODULE_COUNT
            + " modules (util + 14 services + 2 BFFs), found "
            + moduleNodes.getLength());

    File root = ROOT_POM.getParent().toFile();
    for (int i = 0; i < moduleNodes.getLength(); i++) {
      String name = moduleNodes.item(i).getTextContent().trim();
      File dir = new File(root, name);
      assertTrue(
          dir.isDirectory(),
          "Subtask 5.4: module path "
              + name
              + " must resolve to an existing directory under "
              + root);
    }
  }

  @Test
  void ac9_rootPomImportsSpringBootAndCloudBoms() throws Exception {
    // Story 1.1 inversion: the root pom now imports the Spring Boot / Spring Cloud BOMs in
    // <dependencyManagement> so service modules (e.g. services/catalog) inherit versions
    // transitively. util/pom.xml also re-imports the same BOMs at matching versions for its
    // own direct Spring Boot starter deps — the version-skew risk the original test guarded
    // against is mitigated by reading both version coordinates from ${spring-boot.version}
    // and ${spring-cloud.version} in root pom.xml <properties>.
    String raw =
        new String(
            java.nio.file.Files.readAllBytes(ROOT_POM), java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(
        raw.contains("spring-boot-dependencies"),
        "AC #9: root pom must import spring-boot-dependencies BOM so services inherit versions");
    assertTrue(
        raw.contains("spring-cloud-dependencies"),
        "AC #9: root pom must import spring-cloud-dependencies BOM so services inherit versions");
  }
}
