package edu.university.ecs.lab.experiments;

import org.treesitter.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Small helpers shared by the tree-sitter experiments.
 *
 * Tree-sitter reports UTF-8 byte offsets, so node text must be sliced from the UTF-8 bytes of the
 * source, not from the Java String (which is UTF-16). Slicing the String breaks on any file with
 * non-ASCII characters (e.g. the Chinese comments in train-ticket).
 */
public final class TsUtil {

    private TsUtil() {}

    /** A parsed file: keeps the source bytes alongside the tree so node text can be recovered. */
    public record Parsed(TSTree tree, byte[] bytes) {
        public TSNode root() { return tree.getRootNode(); }
        public String text(TSNode node) { return TsUtil.text(node, bytes); }
    }

    /**
     * Plain-Java copy of a tree-sitter node. Every TSNode accessor is a JNI call, so extractors that
     * query the tree repeatedly are far faster on a tree materialized once with a cursor.
     */
    public static final class SNode {
        public final String type;
        public final String fieldName;
        public final boolean named;
        public final boolean error;
        public final int startByte;
        public final int endByte;
        public final SNode parent;
        public final List<SNode> children = new ArrayList<>();
        final List<SNode> namedChildren = new ArrayList<>();

        SNode(TSNode node, String fieldName, SNode parent) {
            this.type = node.getType();
            this.fieldName = fieldName;
            this.named = node.isNamed();
            this.error = node.isError() || node.isMissing();
            this.startByte = node.getStartByte();
            this.endByte = node.getEndByte();
            this.parent = parent;
        }

        SNode(TSNode node, Symbols symbols, int fieldId, SNode parent) {
            int symbol = node.getSymbol();
            this.type = symbols.name(symbol);
            this.fieldName = fieldId == 0 ? null : symbols.fieldNames[fieldId];
            this.named = symbols.named(symbol);
            this.error = symbol == ERROR_SYMBOL;
            this.startByte = node.getStartByte();
            this.endByte = node.getEndByte();
            this.parent = parent;
        }

        public SNode field(String name) {
            for (SNode c : children) if (name.equals(c.fieldName)) return c;
            return null;
        }

        public List<SNode> fields(String name) {
            List<SNode> out = new ArrayList<>();
            for (SNode c : children) if (name.equals(c.fieldName)) out.add(c);
            return out;
        }

        public List<SNode> namedChildren() { return namedChildren; }

        public SNode namedChild(int i) { return namedChildren.get(i); }

        public int namedChildCount() { return namedChildren.size(); }

        public SNode firstChildOfType(String... types) {
            for (SNode c : namedChildren) for (String t : types) if (c.type.equals(t)) return c;
            return null;
        }

        public boolean hasChildOfType(String type) {
            for (SNode c : children) if (c.type.equals(type)) return true;
            return false;
        }

        /** Descendants (including this node) of the given types, in pre-order like JavaParser findAll(). */
        public List<SNode> findAll(String... types) {
            Set<String> wanted = Set.of(types);
            List<SNode> out = new ArrayList<>();
            walk(n -> { if (wanted.contains(n.type)) out.add(n); });
            return out;
        }

        public void walk(Consumer<SNode> visitor) {
            visitor.accept(this);
            for (SNode c : children) c.walk(visitor);
        }

        public SNode ancestorOfType(String... types) {
            for (SNode cur = parent; cur != null; cur = cur.parent) {
                for (String t : types) if (cur.type.equals(t)) return cur;
            }
            return null;
        }
    }

    /** Tree-sitter's builtin ERROR symbol ((TSSymbol) -1). */
    private static final int ERROR_SYMBOL = 65535;

    /** Symbol and field names per language, so materializing reads ints instead of JNI Strings. */
    static final class Symbols {
        final String[] names;
        final boolean[] namedFlags;
        final String[] fieldNames;

        Symbols(TSLanguage language) {
            int count = language.symbolCount();
            names = new String[count];
            namedFlags = new boolean[count];
            for (int i = 0; i < count; i++) {
                names[i] = language.symbolName(i);
                namedFlags[i] = language.symbolType(i) == TSSymbolType.TSSymbolTypeRegular;
            }
            fieldNames = new String[language.fieldCount() + 1];
            for (int i = 1; i < fieldNames.length; i++) fieldNames[i] = language.fieldNameForId(i);
        }

        String name(int symbol) { return symbol == ERROR_SYMBOL ? "ERROR" : names[symbol]; }

        boolean named(int symbol) { return symbol == ERROR_SYMBOL || namedFlags[symbol]; }
    }

    private static final Map<String, Symbols> SYMBOLS = new ConcurrentHashMap<>();

    /** Copies the whole tree into {@link SNode}s using a single cursor walk. */
    public static SNode materialize(TSTree tree) {
        TSLanguage language = tree.getLanguage();
        // name() is null for these grammar builds, so key on a fingerprint of the symbol table
        String key = language.symbolCount() + "/" + language.fieldCount() + "/" + language.symbolName(language.symbolCount() - 1);
        Symbols symbols = SYMBOLS.computeIfAbsent(key, n -> new Symbols(language));
        TSNode root = tree.getRootNode();
        TSTreeCursor cursor = new TSTreeCursor(root);
        SNode sRoot = new SNode(root, null, null);
        SNode current = sRoot;
        if (!cursor.gotoFirstChild()) return sRoot;
        while (true) {
            SNode node = new SNode(cursor.currentNode(), symbols, cursor.currentFieldId(), current);
            current.children.add(node);
            if (node.named) current.namedChildren.add(node);
            if (cursor.gotoFirstChild()) {
                current = node;
                continue;
            }
            while (!cursor.gotoNextSibling()) {
                if (!cursor.gotoParent() || current == sRoot) return sRoot;
                current = current.parent;
            }
        }
    }

    public static String text(SNode node, byte[] bytes) {
        if (node == null) return "";
        return new String(bytes, node.startByte, node.endByte - node.startByte, StandardCharsets.UTF_8);
    }

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /**
     * Strips a leading UTF-8 byte-order mark, if present. Tree-sitter's {@code parseString(String)}
     * does not count the BOM as a byte, so every offset it reports is 3 bytes off from a raw file
     * read that still has it — every node in the file ends up sliced 3 bytes early, both truncating
     * the end and picking up 3 bytes of whatever preceded it. Visual Studio adds this BOM to .cs
     * files by default, so this silently corrupts roughly half of a typical C# codebase if not
     * stripped before both parsing and slicing node text.
     */
    public static byte[] stripUtf8Bom(byte[] bytes) {
        if (bytes.length >= 3 && bytes[0] == UTF8_BOM[0] && bytes[1] == UTF8_BOM[1] && bytes[2] == UTF8_BOM[2]) {
            return java.util.Arrays.copyOfRange(bytes, 3, bytes.length);
        }
        return bytes;
    }

    public static Parsed parse(TSLanguage language, Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        TSParser parser = new TSParser();
        parser.setLanguage(language);
        TSTree tree = parser.parseString(null, new String(bytes, StandardCharsets.UTF_8));
        return new Parsed(tree, bytes);
    }

    public static String text(TSNode node, byte[] bytes) {
        if (isNull(node)) return "";
        return new String(bytes, node.getStartByte(), node.getEndByte() - node.getStartByte(), StandardCharsets.UTF_8);
    }

    public static boolean isNull(TSNode node) {
        return node == null || node.isNull();
    }

    public static TSNode field(TSNode node, String name) {
        if (isNull(node)) return null;
        TSNode child = node.getChildByFieldName(name);
        return isNull(child) ? null : child;
    }

    public static List<TSNode> namedChildren(TSNode node) {
        List<TSNode> out = new ArrayList<>();
        for (int i = 0; i < node.getNamedChildCount(); i++) out.add(node.getNamedChild(i));
        return out;
    }

    public static List<TSNode> children(TSNode node) {
        List<TSNode> out = new ArrayList<>();
        for (int i = 0; i < node.getChildCount(); i++) out.add(node.getChild(i));
        return out;
    }

    public static TSNode firstChildOfType(TSNode node, String... types) {
        Set<String> wanted = Set.of(types);
        for (TSNode child : namedChildren(node)) {
            if (wanted.contains(child.getType())) return child;
        }
        return null;
    }

    /** Pre-order walk, matching JavaParser's findAll() traversal order. */
    public static void walk(TSNode node, Consumer<TSNode> visitor) {
        visitor.accept(node);
        for (int i = 0; i < node.getChildCount(); i++) walk(node.getChild(i), visitor);
    }

    /** All descendants (including the node itself) of the given types, in pre-order. */
    public static List<TSNode> findAll(TSNode node, String... types) {
        Set<String> wanted = Set.of(types);
        List<TSNode> out = new ArrayList<>();
        walk(node, n -> { if (wanted.contains(n.getType())) out.add(n); });
        return out;
    }

    public static TSNode ancestorOfType(TSNode node, String... types) {
        Set<String> wanted = Set.of(types);
        TSNode cur = node.getParent();
        while (!isNull(cur)) {
            if (wanted.contains(cur.getType())) return cur;
            cur = cur.getParent();
        }
        return null;
    }

    /** Number of ERROR / MISSING nodes, i.e. places where the grammar could not parse the file. */
    public static int countErrors(TSNode root) {
        int[] count = {0};
        walk(root, n -> { if (n.isError() || n.isMissing()) count[0]++; });
        return count[0];
    }

    /** Pretty S-expression dump with field names; handy for writing new language rules. */
    public static String dump(TSNode node, byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        dump(node, bytes, null, 0, sb);
        return sb.toString();
    }

    private static void dump(TSNode node, byte[] bytes, String fieldName, int depth, StringBuilder sb) {
        sb.append("  ".repeat(depth));
        if (fieldName != null) sb.append(fieldName).append(": ");
        sb.append(node.getType());
        if (node.getNamedChildCount() == 0) {
            String t = text(node, bytes).replace("\n", "\\n");
            sb.append(" \"").append(t.length() > 60 ? t.substring(0, 60) + "…" : t).append('"');
        }
        sb.append('\n');
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode child = node.getChild(i);
            if (child.isNamed()) dump(child, bytes, node.getFieldNameForChild(i), depth + 1, sb);
        }
    }

    public static TSLanguage languageFor(String name) {
        return switch (name.toLowerCase()) {
            case "java" -> new TreeSitterJava();
            case "javascript", "js" -> new TreeSitterJavascript();
            case "python", "py" -> new TreeSitterPython();
            case "go" -> new TreeSitterGo();
            case "csharp", "c#", "cs" -> new TreeSitterCSharp();
            default -> throw new IllegalArgumentException("Unknown language: " + name);
        };
    }

    /** Usage: TsUtil <language> <file> — prints the syntax tree. */
    public static void main(String[] args) throws Exception {
        Parsed parsed = parse(languageFor(args[0]), Path.of(args[1]));
        System.out.print(dump(parsed.root(), parsed.bytes()));
    }
}
