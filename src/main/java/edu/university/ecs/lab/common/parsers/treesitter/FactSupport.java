package edu.university.ecs.lab.common.parsers.treesitter;

import edu.university.ecs.lab.common.parsers.treesitter.TsUtil.SNode;
import java.util.List;
import java.util.Set;

/**
 * Shared literal normalization and argument access used by the language-specific visitors. These
 * helpers report source shapes; they do not resolve runtime values or execute code.
 */
final class FactSupport {
  private FactSupport() {}

  static final LanguageConfig CONFIG = LanguageConfig.get();
  static final Set<String> HTTP_VERBS =
      Set.of("get", "post", "put", "delete", "patch", "head", "options", "all");

  static final Set<String> STRING_TYPES =
      Set.of(
          "string",
          "template_string",
          "interpreted_string_literal",
          "raw_string_literal",
          "string_literal",
          "verbatim_string_literal",
          "interpolated_string_expression",
          "concatenated_string");

  /**
   * Best-effort value of a URL-like expression: literal parts are kept, interpolations and
   * identifiers become {name}, anything else becomes {?}.
   */
  static String urlOf(SNode n, FactOutput out) {
    if (n == null) return "";
    switch (n.type) {
      case "binary_expression" -> {
        SNode left = n.field("left"), right = n.field("right");
        if (left != null && right != null) return urlOf(left, out) + urlOf(right, out);
        return "{?}";
      }
      case "identifier", "field_identifier" -> {
        return "{" + out.text(n) + "}";
      }
      case "parenthesized_expression" -> {
        return n.namedChildCount() > 0 ? urlOf(n.namedChild(0), out) : "{?}";
      }
      case "argument", "attribute_argument" -> {
        return n.namedChildCount() > 0 ? urlOf(n.namedChild(n.namedChildCount() - 1), out) : "{?}";
      }
      default -> {}
    }

    if (!STRING_TYPES.contains(n.type)) return "{?}";

    StringBuilder sb = new StringBuilder();
    boolean sawPart = false;
    for (SNode c : n.namedChildren()) {
      switch (c.type) {
        case "string_fragment",
            "string_content",
            "interpreted_string_literal_content",
            "raw_string_literal_content",
            "string_literal_content",
            "interpolated_verbatim_string_text",
            "interpolated_string_text",
            "escape_sequence" -> {
          sb.append(out.text(c));
          sawPart = true;
        }
        case "template_substitution", "interpolation" -> {
          SNode e = c.namedChildCount() > 0 ? c.namedChild(0) : null;
          sb.append("{").append(e == null ? "?" : out.text(e)).append("}");
          sawPart = true;
        }
        case "string" -> {
          sb.append(urlOf(c, out));
          sawPart = true;
        } // Python implicit concatenation
        default -> {}
      }
    }

    if (!sawPart) {
      // Leaf literal (e.g. Go raw strings, C# verbatim strings): strip quotes/prefixes
      String t = out.text(n);
      sb.append(t.replaceAll("^[@$a-zA-Z]*[\"'`]+|[\"'`]+$", ""));
    }
    return sb.toString();
  }

  static boolean looksLikePath(String url) {
    return url.startsWith("/") || url.startsWith("{") && url.contains("/");
  }

  static List<SNode> args(SNode call, FactOutput out) {
    SNode a = call.field("arguments");
    if (a == null) return List.of();
    return a.namedChildren().stream().filter(c -> !c.type.contains("comment")).toList();
  }

  static String lastSegment(String s) {
    int i = Math.max(s.lastIndexOf('.'), s.lastIndexOf(':'));
    return i < 0 ? s : s.substring(i + 1);
  }
}
