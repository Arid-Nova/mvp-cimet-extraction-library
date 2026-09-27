package edu.university.ecs.lab.experiments;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import edu.university.ecs.lab.common.config.RepositoryBranchPair;
import edu.university.ecs.lab.common.config.RepositoryConfig;
import edu.university.ecs.lab.common.models.ir.*;
import edu.university.ecs.lab.common.utils.SourceToObjectUtils;

import java.io.File;
import java.nio.file.Path;
import java.util.*;

import static edu.university.ecs.lab.experiments.FileFacts.sortedAttributes;

/**
 * Baseline: runs the original CIMET JavaParser pipeline ({@link SourceToObjectUtils#parseClass})
 * on a single file and flattens the resulting IR into {@link FileFacts}.
 *
 * The repository must already be cloned under ./clone/&lt;repoName&gt;, because CIMET's symbol solver
 * resolves types against that directory.
 */
public class CimetFactsExtractor {

    private final RepositoryConfig repositoryConfig;
    private final Microservice microservice;
    private final JavaParser syntaxOnlyParser = new JavaParser(new ParserConfiguration());

    public CimetFactsExtractor(String repoName) {
        this.repositoryConfig = new RepositoryConfig(
                new RepositoryBranchPair("https://github.com/local/" + repoName + ".git", "local"), "local");
        this.microservice = new Microservice(null, repoName, "local", "local", Path.of("/"));
    }

    /**
     * CIMET swallows parse failures and silently reuses the previous file's AST, so we check
     * parseability separately (outside the timed section) to be able to flag those files.
     */
    public boolean javaParserCanParse(File file) {
        try {
            return syntaxOnlyParser.parse(file).isSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

    public FileFacts extract(File file) {
        AbstractClass abstractClass = SourceToObjectUtils.parseClass(microservice, file, repositoryConfig, false);
        FileFacts facts = new FileFacts(file.getPath());
        if (abstractClass == null) {
            return facts.put("classType", "NONE");
        }
        return toFacts(abstractClass, facts);
    }

    private static FileFacts toFacts(AbstractClass c, FileFacts facts) {
        facts.put("classType", c.getClassType().name());
        facts.put("packageName", c.getPackageName());
        facts.put("classRole", c.getClassRole().name());
        facts.put("classModifiers", c.getProtection() + " final=" + c.getIsFinal()
                + " static=" + c.getIsStatic() + " abstract=" + c.getIsAbstract());

        List<String> supertypes = new ArrayList<>();
        List<String> enumConstants = new ArrayList<>();
        if (c instanceof JClass jc) {
            if (!jc.getExtendedType().isEmpty()) supertypes.add("extends " + jc.getExtendedType());
            jc.getImplementedTypes().forEach(t -> supertypes.add("implements " + t));
        } else if (c instanceof JInterface ji) {
            ji.getExtendedTypes().forEach(t -> supertypes.add("extends " + t));
        } else if (c instanceof JEnum je) {
            je.getImplementedTypes().forEach(t -> supertypes.add("implements " + t));
            enumConstants.addAll(je.getEnumTypes());
        } else if (c instanceof JRecord jr) {
            jr.getImplementedTypes().forEach(t -> supertypes.add("implements " + t));
        }
        facts.put("supertypes", supertypes);
        facts.put("enumConstants", enumConstants);

        facts.put("imports", c.getImports().stream()
                .map(i -> (i.getIsStatic() ? "static " : "") + i.getImportPackage() + "." + i.getImportObject())
                .toList());

        facts.put("annotations", c.getAnnotations().stream()
                .map(a -> a.getName() + sortedAttributes(a.getAttributes()))
                .toList());

        facts.put("fields", c.getFields().stream()
                .map(f -> f.getName() + ":" + f.getFieldType() + " " + f.getProtection()
                        + " static=" + f.getIsStatic() + " final=" + f.getIsFinal() + " init=" + f.getInitializer())
                .toList());

        List<String> methods = new ArrayList<>();
        List<String> endpoints = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        List<String> callTypes = new ArrayList<>();
        List<String> restCalls = new ArrayList<>();
        for (Method m : c.getMethods()) {
            methods.add(methodSignature(m));
            if (m instanceof Endpoint e) {
                endpoints.add(e.getHttpMethod() + " " + e.getUrl() + " -> " + e.getName());
            }
            for (MethodCall mc : m.getMethodCalls()) {
                if (mc instanceof RestCall rc) {
                    restCalls.add(rc.getHttpMethod() + " " + rc.getUrl() + " <- " + rc.getCalledFrom());
                    // Feign clients get a synthetic RestCall with no source counterpart; keep it out of plain calls.
                    if ("RestCallTemplate".equals(rc.getObjectType())) continue;
                }
                calls.add(mc.getCalledFrom() + ": " + mc.getObjectName() + "." + mc.getName()
                        + "(" + mc.getParameterContents() + ")");
                callTypes.add(mc.getCalledFrom() + ": " + mc.getObjectName() + "." + mc.getName()
                        + " : " + mc.getObjectType());
            }
        }
        facts.put("methods", methods);
        facts.put("endpoints", endpoints);
        facts.put("methodCalls", calls);
        facts.put("methodCallObjectTypes", callTypes);
        facts.put("restCalls", restCalls);
        return facts;
    }

    private static String methodSignature(Method m) {
        List<String> params = new ArrayList<>();
        for (Parameter p : m.getParameters()) {
            List<String> anns = p.getAnnotations().stream()
                    .map(a -> "@" + a.getName() + sortedAttributes(a.getAttributes())).sorted().toList();
            params.add(String.join("", anns) + p.getParameterType() + (p.getIsVariableParameter() ? "..." : "") + " " + p.getName());
        }
        Collections.sort(params);
        List<String> thrown = new ArrayList<>(m.getThrownExceptions());
        Collections.sort(thrown);
        return m.getName() + "(" + String.join(", ", params) + ") " + m.getProtection()
                + " abstract=" + m.getIsAbstract() + " static=" + m.getIsStatic() + " final=" + m.getIsFinal()
                + " throws=" + thrown;
    }
}
