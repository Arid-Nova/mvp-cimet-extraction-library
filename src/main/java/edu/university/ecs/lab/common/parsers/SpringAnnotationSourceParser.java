package edu.university.ecs.lab.common.parsers;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.expr.AnnotationExpr;
import edu.university.ecs.lab.common.config.RepositoryConfig;
import edu.university.ecs.lab.common.models.enums.ClassRole;
import edu.university.ecs.lab.common.models.enums.EndpointTemplate;
import edu.university.ecs.lab.common.models.ir.AbstractClass;
import edu.university.ecs.lab.common.models.ir.Endpoint;
import edu.university.ecs.lab.common.models.ir.JClass;
import edu.university.ecs.lab.common.models.ir.Location;
import edu.university.ecs.lab.common.models.ir.Method;
import edu.university.ecs.lab.common.models.ir.Microservice;
import edu.university.ecs.lab.common.utils.FileUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spring MVC endpoints in Groovy and Kotlin sources. Neither language has a parser here, but a Spring
 * controller written in either uses exactly the annotations a Java one does - so this finds the mapping
 * annotations and the class/method each one sits on, converts each annotation's arguments to Java
 * syntax, and hands it to the same {@link EndpointTemplate} the Java parser uses. Only endpoints (and
 * the classes declaring them) are extracted; fields, calls and the rest of the file are not modeled.
 */
public class SpringAnnotationSourceParser implements LanguageParser {

    private static final Set<String> EXTENSIONS = Set.of(".groovy", ".kt");
    private static final Set<String> LIMITATIONS = Set.of(
            "Groovy/Kotlin: only Spring MVC mapping annotations and the classes declaring them are extracted.",
            "A path given by a constant rather than a literal is reported by the constant's name.");

    private static final Pattern CLASS_DECLARATION = Pattern.compile(
            "(?:\\b(?:public|private|protected|internal|abstract|open|final|data|sealed|static|inner|enum|annotation)\\s+)*"
                    + "\\b(class|interface|object)\\s+([A-Za-z_]\\w*)");
    private static final Pattern IDENTIFIER_BEFORE_PAREN = Pattern.compile("([A-Za-z_]\\w*)\\s*\\($");

    @Override
    public boolean canParse(File sourceFile) {
        return sourceFile != null && EXTENSIONS.stream().anyMatch(sourceFile.getName()::endsWith);
    }

    @Override
    public ParserCapability getCapability() {
        return new ParserCapability("groovy/kotlin (Spring annotations)", EXTENSIONS, LIMITATIONS);
    }

    @Override
    public ParseResult parse(Microservice microservice, File sourceFile, RepositoryConfig config, boolean filterOutUnknownClassRoles) {
        try {
            String source = Files.readString(sourceFile.toPath(), StandardCharsets.UTF_8);
            String gitPath = FileUtils.localPathToGitPath(sourceFile.getPath(), config.getRepoName());
            List<AbstractClass> classes = extract(microservice, Path.of(gitPath), source);
            AbstractClass returned = null;
            for (AbstractClass c : classes) {
                if (filterOutUnknownClassRoles && c.getClassRole() == ClassRole.UNKNOWN) continue;
                if (returned == null) returned = c;
                else microservice.addAbstractClass(c);
            }
            return ParseResult.success(returned);
        } catch (Exception e) {
            return ParseResult.failure("Spring annotation scan failed on " + sourceFile.getPath() + ": " + e);
        }
    }

    private record Ann(String simpleName, String text, int start, int end) {}

    private record TypeDeclaration(String name, int start, int bodyStart, int bodyEnd, List<Ann> annotations,
                                   boolean isAbstract, String superclass) {}

    static List<AbstractClass> extract(Microservice microservice, Path gitPath, String source) {
        boolean[] code = codeMask(source);
        List<List<Ann>> runs = annotationRuns(source, code);

        Map<Integer, List<Ann>> runByDeclarationStart = new LinkedHashMap<>();
        for (List<Ann> run : runs) {
            runByDeclarationStart.put(skipWhitespace(source, run.get(run.size() - 1).end()), run);
        }
        List<TypeDeclaration> types = typeDeclarations(source, code, runByDeclarationStart);

        Map<TypeDeclaration, JClass> classes = new LinkedHashMap<>();
        for (List<Ann> run : runs) {
            Ann mapping = run.stream().filter(a -> EndpointTemplate.ENDPOINT_ANNOTATIONS.contains(a.simpleName()))
                    .findFirst().orElse(null);
            if (mapping == null) continue;
            int declarationStart = skipWhitespace(source, run.get(run.size() - 1).end());
            if (types.stream().anyMatch(t -> t.start() == declarationStart)) continue; // class-level mapping
            String methodName = methodNameAt(source, declarationStart);
            TypeDeclaration owner = innermostEnclosing(types, mapping.start());
            if (methodName == null || owner == null || hasAnnotation(owner, "FeignClient")) continue;

            AnnotationExpr classMapping = owner.annotations().stream()
                    .filter(a -> a.simpleName().equals("RequestMapping"))
                    .findFirst().map(a -> toJavaAnnotation(a.text())).orElse(null);
            AnnotationExpr methodMapping = toJavaAnnotation(mapping.text());
            if (methodMapping == null) continue;

            JClass owningClass = classes.computeIfAbsent(owner, t -> newClass(microservice, gitPath, t, classes.isEmpty()));
            Location location = new Location();
            location.setStartLine(lineOf(source, declarationStart));
            location.setEndLine(location.getStartLine());
            for (AnnotationExpr singlePath : EndpointTemplate.splitPaths(methodMapping)) {
                EndpointTemplate template = new EndpointTemplate(classMapping, singlePath);
                Method base = new Method(owningClass, methodName, location);
                owningClass.getMethods().add(new Endpoint(base, template.getUrl(), template.getHttpMethod()));
            }
        }
        for (TypeDeclaration type : types) {
            if (!classes.containsKey(type) && (hasAnnotation(type, "RestController") || hasAnnotation(type, "Controller"))) {
                classes.put(type, newClass(microservice, gitPath, type, classes.isEmpty()));
            }
        }
        return new ArrayList<>(classes.values());
    }

    private static JClass newClass(Microservice microservice, Path gitPath, TypeDeclaration type, boolean first) {
        Path path = first ? gitPath : Path.of(gitPath + "##" + type.name());
        JClass jClass = new JClass(microservice, path, null, type.superclass(), Set.of());
        jClass.setName(type.name());
        jClass.setIsAbstract(type.isAbstract());
        for (Ann a : type.annotations()) {
            if (!a.simpleName().equals("RequestMapping")) continue;
            AnnotationExpr expr = toJavaAnnotation(a.text());
            if (expr != null && expr.getRange().isPresent()) {
                jClass.getAnnotations().add(new edu.university.ecs.lab.common.models.ir.Annotation(jClass, expr));
            }
        }
        jClass.setClassRole(hasAnnotation(type, "RestController") || hasAnnotation(type, "Controller")
                ? ClassRole.CONTROLLER : ClassRole.UNKNOWN);
        return jClass;
    }

    private static boolean hasAnnotation(TypeDeclaration type, String simpleName) {
        return type.annotations().stream().anyMatch(a -> a.simpleName().equals(simpleName));
    }

    /** true where the character is code - false inside comments and string literals. */
    private static boolean[] codeMask(String s) {
        boolean[] code = new boolean[s.length()];
        int i = 0;
        while (i < s.length()) {
            if (s.startsWith("//", i)) {
                while (i < s.length() && s.charAt(i) != '\n') i++;
            } else if (s.startsWith("/*", i)) {
                int end = s.indexOf("*/", i + 2);
                i = end < 0 ? s.length() : end + 2;
            } else if (s.startsWith("\"\"\"", i) || s.startsWith("'''", i)) {
                String q = s.substring(i, i + 3);
                int end = s.indexOf(q, i + 3);
                i = end < 0 ? s.length() : end + 3;
            } else if (s.charAt(i) == '"' || s.charAt(i) == '\'') {
                char q = s.charAt(i++);
                while (i < s.length() && s.charAt(i) != q && s.charAt(i) != '\n') {
                    if (s.charAt(i) == '\\') i++;
                    i++;
                }
                i++;
            } else {
                code[i++] = true;
            }
        }
        return code;
    }

    /** Annotations grouped into runs - consecutive annotations separated only by whitespace. */
    private static List<List<Ann>> annotationRuns(String s, boolean[] code) {
        List<List<Ann>> runs = new ArrayList<>();
        List<Ann> current = new ArrayList<>();
        int previousEnd = -1;
        for (int i = 0; i < s.length(); i++) {
            if (!code[i] || s.charAt(i) != '@' || (i > 0 && Character.isJavaIdentifierPart(s.charAt(i - 1)))) continue;
            int nameEnd = i + 1;
            while (nameEnd < s.length() && (Character.isJavaIdentifierPart(s.charAt(nameEnd)) || s.charAt(nameEnd) == '.')) nameEnd++;
            if (nameEnd == i + 1) continue;
            String name = s.substring(i + 1, nameEnd);
            int end = nameEnd;
            int afterName = skipInlineWhitespace(s, nameEnd);
            if (afterName < s.length() && s.charAt(afterName) == '(') {
                int close = matchingParen(s, code, afterName);
                if (close < 0) continue;
                end = close + 1;
            }
            if (previousEnd >= 0 && !s.substring(previousEnd, i).isBlank()) {
                runs.add(current);
                current = new ArrayList<>();
            }
            current.add(new Ann(name.substring(name.lastIndexOf('.') + 1), s.substring(i, end), i, end));
            previousEnd = end;
            i = end - 1;
        }
        if (!current.isEmpty()) runs.add(current);
        return runs;
    }

    private static List<TypeDeclaration> typeDeclarations(String s, boolean[] code, Map<Integer, List<Ann>> runs) {
        List<TypeDeclaration> types = new ArrayList<>();
        Matcher m = CLASS_DECLARATION.matcher(s);
        while (m.find()) {
            if (!code[m.start(1)]) continue;
            int bodyStart = -1;
            for (int i = m.end(); i < s.length(); i++) {
                if (!code[i]) continue;
                char c = s.charAt(i);
                if (c == '(') {
                    int close = matchingParen(s, code, i);
                    if (close < 0) break;
                    i = close;
                } else if (c == '{') {
                    bodyStart = i;
                    break;
                } else if (c == ';' || c == '}') {
                    break;
                }
            }
            if (bodyStart < 0) continue;
            int bodyEnd = matchingBrace(s, code, bodyStart);
            String modifiers = s.substring(m.start(), m.start(1));
            types.add(new TypeDeclaration(m.group(2), m.start(), bodyStart, bodyEnd < 0 ? s.length() : bodyEnd,
                    runs.getOrDefault(m.start(), List.of()), modifiers.contains("abstract"),
                    superclassOf(s, code, m.end(), bodyStart)));
        }
        return types;
    }

    private static final Pattern GROOVY_EXTENDS = Pattern.compile("\\bextends\\s+([A-Za-z_][\\w.]*)");
    private static final Pattern KOTLIN_SUPERCLASS = Pattern.compile("^\\s*:\\s*([A-Za-z_][\\w.]*)\\s*(?:<[^>]*>)?\\s*\\(");

    /** Groovy "extends Base"; Kotlin ": Base(...)" after the primary constructor. Simple name, or "". */
    private static String superclassOf(String s, boolean[] code, int afterName, int bodyStart) {
        String header = s.substring(afterName, bodyStart);
        Matcher groovy = GROOVY_EXTENDS.matcher(header);
        if (groovy.find()) return simpleName(groovy.group(1));
        int i = skipWhitespace(s, afterName);
        if (i < bodyStart && s.charAt(i) == '(') {
            int close = matchingParen(s, code, i);
            if (close > 0 && close < bodyStart) i = close + 1;
        }
        Matcher kotlin = KOTLIN_SUPERCLASS.matcher(s.substring(i, bodyStart));
        return kotlin.find() ? simpleName(kotlin.group(1)) : "";
    }

    private static String simpleName(String qualified) {
        return qualified.substring(qualified.lastIndexOf('.') + 1);
    }

    private static TypeDeclaration innermostEnclosing(List<TypeDeclaration> types, int position) {
        TypeDeclaration best = null;
        for (TypeDeclaration t : types) {
            if (t.bodyStart() < position && position < t.bodyEnd() && (best == null || t.bodyStart() > best.bodyStart())) best = t;
        }
        return best;
    }

    /** Name of the function/method whose declaration starts here: the identifier right before its "(". */
    private static String methodNameAt(String s, int start) {
        int paren = s.indexOf('(', start);
        if (paren < 0) return null;
        String head = s.substring(start, paren + 1);
        if (head.contains("{") || head.contains(";") || head.contains("=")) return null;
        Matcher m = IDENTIFIER_BEFORE_PAREN.matcher(head);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Groovy/Kotlin annotation arguments in Java syntax: [a, b] and arrayOf(a, b) array literals become
     * {a, b}; single- and triple-quoted strings become ordinary double-quoted ones.
     */
    static AnnotationExpr toJavaAnnotation(String text) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            if (text.startsWith("\"\"\"", i) || text.startsWith("'''", i)) {
                String q = text.substring(i, i + 3);
                int end = text.indexOf(q, i + 3);
                if (end < 0) end = text.length() - 3;
                out.append(javaString(text.substring(i + 3, end)));
                i = end + 3;
            } else if (text.charAt(i) == '"' || text.charAt(i) == '\'') {
                char q = text.charAt(i);
                int j = i + 1;
                StringBuilder value = new StringBuilder();
                while (j < text.length() && text.charAt(j) != q) {
                    if (text.charAt(j) == '\\' && j + 1 < text.length()) {
                        value.append(text.charAt(j + 1));
                        j += 2;
                    } else {
                        value.append(text.charAt(j++));
                    }
                }
                out.append(javaString(value.toString()));
                i = j + 1;
            } else if (text.startsWith("arrayOf(", i)) {
                out.append('{');
                i += "arrayOf(".length();
                int depth = 1;
                while (i < text.length() && depth > 0) {
                    char c = text.charAt(i);
                    if (c == '(') depth++;
                    if (c == ')' && --depth == 0) {
                        out.append('}');
                        i++;
                        break;
                    }
                    out.append(c);
                    i++;
                }
            } else {
                char c = text.charAt(i++);
                out.append(c == '[' ? '{' : c == ']' ? '}' : c);
            }
        }
        try {
            return StaticJavaParser.parseAnnotation(out.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static String javaString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }

    private static int matchingParen(String s, boolean[] code, int open) {
        return matching(s, code, open, '(', ')');
    }

    private static int matchingBrace(String s, boolean[] code, int open) {
        return matching(s, code, open, '{', '}');
    }

    private static int matching(String s, boolean[] code, int open, char openChar, char closeChar) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            if (!code[i]) continue;
            if (s.charAt(i) == openChar) depth++;
            else if (s.charAt(i) == closeChar && --depth == 0) return i;
        }
        return -1;
    }

    private static int skipWhitespace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private static int skipInlineWhitespace(String s, int i) {
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
        return i;
    }

    private static int lineOf(String s, int position) {
        int line = 1;
        for (int i = 0; i < position && i < s.length(); i++) {
            if (s.charAt(i) == '\n') line++;
        }
        return line;
    }
}
