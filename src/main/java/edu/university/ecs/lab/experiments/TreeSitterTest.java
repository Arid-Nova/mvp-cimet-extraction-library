package edu.university.ecs.lab.experiments;

import edu.university.ecs.lab.common.parsers.treesitter.TsUtil;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterJava;

/** Manual single-file grammar/classification probe; not the production Java parser. */
public class TreeSitterTest {

  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      throw new IllegalArgumentException("Usage: TreeSitterTest SOURCE.java");
    }
    Path sourcePath = Path.of(args[0]);
    if (!Files.isRegularFile(sourcePath)) {
      throw new IllegalArgumentException("Source must be an existing Java file: " + sourcePath);
    }
    String source = Files.readString(sourcePath);

    long startTime = System.nanoTime();
    long startMem = usedMemory();

    TSParser parser = new TSParser();
    TSLanguage java = new TreeSitterJava();
    parser.setLanguage(java);
    TSTree tree = parser.parseString(null, source);

    List<String> classes = new ArrayList<>();
    List<String> methods = new ArrayList<>();
    List<String> annotations = new ArrayList<>();

    walk(tree.getRootNode(), source, classes, methods, annotations);

    // NEW: semantic classification, ported from LaCIMET's language_config.json rules for Java
    String className = classes.isEmpty() ? "" : classes.get(0);
    String role = classifyRole(className, annotations);

    long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
    long usedMemKb = (usedMemory() - startMem) / 1024;

    System.out.println("=== Tree-sitter extraction + semantic classification ===");
    System.out.println("Source:      " + sourcePath);
    System.out.println("Class:       " + className);
    System.out.println("Role:        " + role);
    System.out.println("Methods:     " + methods);
    System.out.println("Annotations: " + annotations);
    System.out.println();
    System.out.println("Time taken: " + elapsedMs + " ms");
    System.out.println("Approx memory used: " + usedMemKb + " KB");
  }

  /**
   * Semantic role classification, ported from LaCIMET's language_config.json Java rules
   * (annotations + name_contains patterns).
   */
  private static String classifyRole(String className, List<String> annotations) {
    // CONTROLLER
    if (annotations.stream().anyMatch(a -> a.contains("RestController") || a.contains("Controller"))
        || className.contains("Controller")) {
      return "CONTROLLER";
    }
    // SERVICE
    if (annotations.stream().anyMatch(a -> a.contains("Service"))
        || className.contains("Service")
        || className.contains("ServiceImpl")) {
      return "SERVICE";
    }
    // REPOSITORY
    if (annotations.stream().anyMatch(a -> a.contains("Repository"))
        || className.contains("Repository")
        || className.contains("Dao")) {
      return "REPOSITORY";
    }
    // ENTITY
    if (annotations.stream().anyMatch(a -> a.contains("Entity"))) {
      return "ENTITY";
    }
    return "UNKNOWN";
  }

  private static void walk(
      TSNode node,
      String source,
      List<String> classes,
      List<String> methods,
      List<String> annotations) {
    String type = node.getType();
    switch (type) {
      case "class_declaration" -> {
        TSNode name = node.getChildByFieldName("name");
        if (name != null) classes.add(nodeText(name, source));
      }
      case "method_declaration" -> {
        TSNode name = node.getChildByFieldName("name");
        if (name != null) methods.add(nodeText(name, source));
      }
      case "annotation", "marker_annotation" -> annotations.add(nodeText(node, source));
    }
    for (int i = 0; i < node.getChildCount(); i++) {
      walk(node.getChild(i), source, classes, methods, annotations);
    }
  }

  // Tree-sitter offsets are UTF-8 byte offsets, so slice the bytes, not the (UTF-16) String
  private static String nodeText(TSNode node, String source) {
    return TsUtil.text(node, source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  /** Heap occupancy delta is a rough exploratory observation, not a peak-memory measurement. */
  private static long usedMemory() {
    Runtime rt = Runtime.getRuntime();
    return rt.totalMemory() - rt.freeMemory();
  }
}
