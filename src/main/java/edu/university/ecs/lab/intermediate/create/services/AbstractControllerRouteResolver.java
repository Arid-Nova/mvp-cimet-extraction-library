package edu.university.ecs.lab.intermediate.create.services;

import edu.university.ecs.lab.common.models.ir.AbstractClass;
import edu.university.ecs.lab.common.models.ir.Annotation;
import edu.university.ecs.lab.common.models.ir.Endpoint;
import edu.university.ecs.lab.common.models.ir.JClass;
import edu.university.ecs.lab.common.models.ir.Method;
import edu.university.ecs.lab.common.models.ir.Microservice;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Handler methods declared on an abstract controller are served only through its concrete subclasses,
 * each under its own class-level prefix (spinnaker clouddriver's AbstractDockerRegistryLookupController
 * serves /find as /dockerRegistry/images/find and /dockerRegistry/charts/find); a subclass that maps
 * the method itself replaces the inherited route (igor's four CommitControllers). Read per class, the
 * abstract class looked like it served its routes at its own bare paths. Runs once per microservice,
 * after every file is parsed.
 */
public final class AbstractControllerRouteResolver {

    private AbstractControllerRouteResolver() {}

    public static void apply(Microservice microservice) {
        List<AbstractClass> classes = microservice.getClasses().stream().toList();
        boolean changed = false;
        for (AbstractClass base : classes) {
            if (!Boolean.TRUE.equals(base.getIsAbstract())) continue;
            List<Endpoint> baseEndpoints = base.getMethods().stream()
                    .filter(Endpoint.class::isInstance).map(Endpoint.class::cast).toList();
            if (baseEndpoints.isEmpty()) continue;

            List<AbstractClass> subclasses = classes.stream()
                    .filter(c -> c instanceof JClass j && base.getName().equals(j.getExtendedType()))
                    .filter(c -> !Boolean.TRUE.equals(c.getIsAbstract()))
                    .toList();
            if (subclasses.isEmpty()) continue;

            List<Method> served = new ArrayList<>();
            for (AbstractClass subclass : subclasses) {
                Set<String> remapped = subclass.getMethods().stream()
                        .filter(Endpoint.class::isInstance).map(Method::getName).collect(Collectors.toSet());
                String prefix = classLevelPath(subclass);
                for (Endpoint inherited : baseEndpoints) {
                    if (remapped.contains(inherited.getName())) continue;
                    served.add(new Endpoint(new Method(inherited), joinPaths(prefix, inherited.getUrl()), inherited.getHttpMethod()));
                }
            }
            base.getMethods().removeAll(baseEndpoints);
            baseEndpoints.forEach(e -> base.getMethods().add(new Method(e)));
            base.getMethods().addAll(served);
            changed = true;
        }
        if (changed) {
            // A class's hash covers its methods, so the sets holding the changed classes need rebuilding.
            microservice.setControllers(new HashSet<>(microservice.getControllers()));
            microservice.setServices(new HashSet<>(microservice.getServices()));
            microservice.setRepositories(new HashSet<>(microservice.getRepositories()));
            microservice.setEntities(new HashSet<>(microservice.getEntities()));
            microservice.setFeignClients(new HashSet<>(microservice.getFeignClients()));
            microservice.setUnknowns(new HashSet<>(microservice.getUnknowns()));
        }
    }

    /**
     * The class-level @RequestMapping path (its first, if several), or "" when there is none. A Java
     * class's annotation set holds every annotation in its file, method-level ones included - the
     * class-level one is the earliest.
     */
    private static String classLevelPath(AbstractClass c) {
        Annotation a = c.getAnnotations().stream()
                .filter(x -> x.getName().equals("RequestMapping") && x.getAttributes() != null)
                .min(java.util.Comparator.comparingInt(x -> x.getLocation() == null ? Integer.MAX_VALUE : x.getLocation().getStartLine()))
                .orElse(null);
        if (a != null) {
            Map<String, String> attrs = a.getAttributes();
            for (String key : List.of("default", "value", "path")) {
                String v = attrs.get(key);
                if (v == null) continue;
                String first = v.replaceAll("^\\{|}$", "").split(",")[0].trim().replaceAll("^\"|\"$", "");
                return first;
            }
        }
        return "";
    }

    private static String joinPaths(String prefix, String path) {
        if (prefix == null || prefix.isEmpty()) return path;
        String p = prefix.startsWith("/") ? prefix : "/" + prefix;
        if (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (path == null || path.isEmpty() || path.equals("/")) return p;
        return p + (path.startsWith("/") ? path : "/" + path);
    }
}
