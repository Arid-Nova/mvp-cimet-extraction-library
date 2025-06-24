package edu.university.ecs.lab.common.utils;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.model.typesystem.ReferenceTypeImpl;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import edu.university.ecs.lab.common.config.Config;
import edu.university.ecs.lab.common.models.enums.*;
import edu.university.ecs.lab.common.models.ir.*;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Static utility class for parsing a file and returning associated models from code structure.
 */
public class SourceToObjectUtils {
    private static CompilationUnit cu;
    private static Path path;
    private static String className;
    private static String packageName;
    private static CombinedTypeSolver combinedTypeSolver;
    private static Config config;


    private static void generateStaticValues(File sourceFile, Config config1) {
        // Parse the highest level node being compilation unit
        config = config1;
        try {
            cu = StaticJavaParser.parse(sourceFile);
        } catch (Exception e) {
            return;
        }
        if (!cu.findAll(PackageDeclaration.class).isEmpty()) {
            packageName = cu.findAll(PackageDeclaration.class).get(0).getNameAsString();
        }
        path = Path.of(FileUtils.localPathToGitPath(sourceFile.getPath(), config.getRepoName()));

        TypeSolver reflectionTypeSolver = new ReflectionTypeSolver();
        TypeSolver javaParserTypeSolver = new JavaParserTypeSolver(FileUtils.getRepositoryPath(config.getRepoName()));

        combinedTypeSolver = new CombinedTypeSolver();
        combinedTypeSolver.add(reflectionTypeSolver);
        combinedTypeSolver.add(javaParserTypeSolver);

        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(combinedTypeSolver);
        StaticJavaParser.getConfiguration().setSymbolResolver(symbolSolver);
        className = sourceFile.getName().replace(".java", "");
    }

    /**
     * This method parses a Java class file and return an AbstractClass object.
     *
     * @param sourceFile the file to parse
     * @return the AbstractClass object representing the file
     */
    public static AbstractClass parseClass(Microservice microservice, File sourceFile, Config config, Boolean filterOutUnknownClassRoles) {
        // Guard condition
        if(Objects.isNull(sourceFile) || FileUtils.isConfigurationFile(sourceFile.getPath())) {
            return null;
        }

        generateStaticValues(sourceFile, config);

        // Calculate early to determine classrole based on annotation, filter for class based annotations only
        Set<AnnotationExpr> classAnnotations = filterClassAnnotations();
        AnnotationExpr requestMapping = classAnnotations.stream().filter(ae -> ae.getNameAsString().equals("RequestMapping")).findFirst().orElse(null);

        // Identify instances of MongoRepository and CrudRepository
        List<ClassOrInterfaceDeclaration> classInterfaceDecs = cu.findAll(ClassOrInterfaceDeclaration.class);
        Set<String> s = new HashSet<>();
        if (!classInterfaceDecs.isEmpty())
            s = classInterfaceDecs.get(0).getExtendedTypes().stream().map(NodeWithSimpleName::getNameAsString).collect(Collectors.toSet());

        ClassRole classRole = parseClassRole(classAnnotations, s);

        // Return unknown classRoles where annotation not found
        if (filterOutUnknownClassRoles && classRole.equals(ClassRole.UNKNOWN)) {
            return null;
        }

        AbstractClass abstractClass = null;

        if(classRole == ClassRole.FEIGN_CLIENT) {
            abstractClass = handleFeignClient(microservice, requestMapping, classAnnotations);
        } else if(classRole == ClassRole.REP_REST_RSC) {
            abstractClass = handleRepositoryRestResource(microservice, requestMapping, classAnnotations);
        } else {
            abstractClass = prepareClass(microservice, path, packageName);

            if (abstractClass == null) {
                return null;
            }

            abstractClass.setClassRole(classRole);
            abstractClass.setFields(parseFields(abstractClass, cu.findAll(FieldDeclaration.class)));
            abstractClass.setAnnotations(parseAnnotations(abstractClass, cu.findAll(AnnotationExpr.class)));
            abstractClass.setMethods(parseMethods(abstractClass, cu.findAll(MethodDeclaration.class), requestMapping));
            abstractClass.setImports(parseImports(abstractClass, cu.findAll(ImportDeclaration.class)));
        }

        // Build the AbstractClass
        return abstractClass;

    }

    /**
     * This method parses importDeclarations list and returns a Set of Import models
     *
     * @param importDeclarations the list of importDeclarations to be parsed
     * @return a set of Import models representing the ImportDeclarations
     */
    public static Set<Import> parseImports(edu.university.ecs.lab.common.models.ir.Node parent, List<ImportDeclaration> importDeclarations) {
        HashSet<Import> imports = new HashSet<>();

        for (ImportDeclaration impDec : importDeclarations) {
            imports.add(new Import(parent, impDec));
        }
        return imports;
    }

    /**
     * This method parses methodDeclarations list and returns a Set of Method models
     *
     * @param methodDeclarations the list of methodDeclarations to be parsed
     * @return a set of Method models representing the MethodDeclarations
     */
    public static Set<Method> parseMethods(edu.university.ecs.lab.common.models.ir.Node parent, List<MethodDeclaration> methodDeclarations, AnnotationExpr requestMapping) {
        // Get params and returnType
        Set<Method> methods = new HashSet<>();

        for (MethodDeclaration methodDeclaration : methodDeclarations) {
            Method method = new Method(parent, methodDeclaration);

            method = convertValidEndpoints(methodDeclaration, method, requestMapping);

            methods.add(method);
        }

        return methods;
    }

    /**
     * This method converts a valid Method to an Endpoint
     *
     * @param methodDeclaration the MethodDeclaration associated with Method
     * @param method            the Method to be converted
     * @param requestMapping    the class level requestMapping
     * @return returns method if it is invalid, otherwise a new Endpoint
     */
    public static Method convertValidEndpoints(MethodDeclaration methodDeclaration, Method method, AnnotationExpr requestMapping) {
        for (AnnotationExpr ae : methodDeclaration.getAnnotations()) {
            String ae_name = ae.getNameAsString();
            if (EndpointTemplate.ENDPOINT_ANNOTATIONS.contains(ae_name)) {
                EndpointTemplate endpointTemplate = new EndpointTemplate(requestMapping, ae);

                // By Spring documentation, only the first valid @Mapping annotation is considered;
                // And getAnnotations() return them in order, so we can return immediately
                return new Endpoint(method, endpointTemplate.getUrl(), endpointTemplate.getHttpMethod());
            }
        }

        return method;
    }

    /**
     * This method parses a MethodCallExpr list and returns a Set of MethodCall models
     *
     * @param methodCallExprs the list of methodCallExprs to be parsed
     * @return a set of MethodCall models representing MethodCallExpressions
     */
    public static List<MethodCall> parseMethodCalls(edu.university.ecs.lab.common.models.ir.Node parent, List<MethodCallExpr> methodCallExprs) {
        List<MethodCall> methodCalls = new ArrayList<>();

        // loop through method calls
        for (MethodCallExpr mce : methodCallExprs) {
            MethodCall methodCall = new MethodCall(parent, mce, getCallingObjectType(mce));

            methodCall = convertValidRestCalls(mce, methodCall);
            methodCalls.add(methodCall);
        }

        return methodCalls;
    }

    /**
     * This method converts a valid MethodCall to an RestCall
     *
     * @param methodCallExpr the MethodDeclaration associated with Method
     * @param methodCall     the MethodCall to be converted
     * @return returns methodCall if it is invalid, otherwise a new RestCall
     */
    public static MethodCall convertValidRestCalls(MethodCallExpr methodCallExpr, MethodCall methodCall) {
        if ((!RestCallTemplate.REST_OBJECTS.contains(methodCall.getObjectType()) || !RestCallTemplate.REST_METHODS.contains(methodCallExpr.getNameAsString()))) {
            return methodCall;
        }

        RestCallTemplate restCallTemplate = new RestCallTemplate(methodCallExpr,methodCall, cu);

        if (restCallTemplate.getUrl().isEmpty()) {
            return methodCall;
        }

        return new RestCall(methodCall, restCallTemplate.getUrl(), restCallTemplate.getHttpMethod());
    }

    /**
     * This method converts a list of FieldDeclarations to a set of Field models
     *
     * @param fieldDeclarations the field declarations to parse
     * @return the set of Field models
     */
    private static Set<Field> parseFields(edu.university.ecs.lab.common.models.ir.Node parent, List<FieldDeclaration> fieldDeclarations) {
        Set<Field> javaFields = new HashSet<>();

        // loop through class declarations
        for (FieldDeclaration fd : fieldDeclarations) {
            for (VariableDeclarator variable : fd.getVariables()) {
                javaFields.add(new Field(parent, fd, variable));
            }
        }

        return javaFields;
    }


    /**
     * Get the name of the object a method is being called from (callingObj.methodName())
     *
     * @return the name of the object the method is being called from
     */
    private static String getCallingObjectName(MethodCallExpr mce) {
        Expression scope = mce.getScope().orElse(null);

        if (Objects.nonNull(scope) && scope instanceof NameExpr) {
            NameExpr fae = scope.asNameExpr();
            return fae.getNameAsString();
        }

        return "";
    }

    private static String getCallingObjectType(MethodCallExpr mce) {
        Expression scope = mce.getScope().orElse(null);

        if (Objects.isNull(scope)) {
            return "";
        }

        try {
            // Resolve the type of the object
            var resolvedType = JavaParserFacade.get(combinedTypeSolver).getType(scope);
            List<String> parts = List.of(((ReferenceTypeImpl) resolvedType).getQualifiedName().split("\\."));
            if(parts.isEmpty()) {
                return "";
            }

            return parts.get(parts.size() - 1);
        } catch (Exception e) {
            if(e instanceof UnsolvedSymbolException && ((UnsolvedSymbolException) e).getName() != null) {
                return ((UnsolvedSymbolException) e).getName();
            }
            return "";
        }
    }


    /**
     * This method parses a list of annotation expressions and returns a set of Annotation models
     *
     * @param annotationExprs the annotation expressions to parse
     * @return the Set of Annotation models
     */
    public static Set<Annotation> parseAnnotations(edu.university.ecs.lab.common.models.ir.Node parent, Iterable<AnnotationExpr> annotationExprs) {
        Set<Annotation> annotations = new HashSet<>();

        for (AnnotationExpr ae : annotationExprs) {
            annotations.add(new Annotation(parent, ae));
        }

        return annotations;
    }

    /**
     * This method searches a list of Annotation expressions and returns a ClassRole found
     *
     * @param annotations the list of annotations to search
     * @return the ClassRole determined
     */
    private static ClassRole parseClassRole(Set<AnnotationExpr> annotations, Set<String> extendedTypes) {
        for (AnnotationExpr annotation : annotations) {
            switch (annotation.getNameAsString()) {
                case "RestController":
                case "Controller":
                    return ClassRole.CONTROLLER;
                case "Service":
                    return ClassRole.SERVICE;
                case "Repository":
                    return ClassRole.REPOSITORY;
                case "RepositoryRestResource":
                    return ClassRole.REP_REST_RSC;
                case "Entity":
                case "Embeddable":
                    return ClassRole.ENTITY;
                case "FeignClient":
                    return ClassRole.FEIGN_CLIENT;
            }
        }
        for (String type : extendedTypes) {
            if (type.equals("MongoRepository") || type.equals("CrudRepository"))
                return ClassRole.REPOSITORY;
        }
        return ClassRole.UNKNOWN;
    }

    /**
     * Get the name of the microservice based on the file
     *
     * @param sourceFile the file we are getting microservice name for
     * @return
     */
    private static String getMicroserviceName(File sourceFile) {
        List<String> split = Arrays.asList(sourceFile.getPath().split(FileUtils.SPECIAL_SEPARATOR));
        return split.get(3);
    }

    /**
     * FeignClient represents an interface for making rest calls to a service
     * other than the current one. As such this method converts feignClient
     * interfaces into a service class whose methods simply contain the exact
     * rest call outlined by the interface annotations.
     *
     * @param classAnnotations
     * @return
     */
    private static AbstractClass handleFeignClient(Microservice microservice, AnnotationExpr requestMapping, Set<AnnotationExpr> classAnnotations) {
        AbstractClass abstractClass = prepareClass(microservice, path, packageName);

        if (abstractClass == null) {
            return null;
        }

        // Parse the methods
        Set<Method> methods = parseMethods(abstractClass, cu.findAll(MethodDeclaration.class), requestMapping);

        // New methods for conversion
        Set<Method> newMethods = new HashSet<>();

        // For each method that is detected as an endpoint convert into a Method + RestCall
        for(Method method : methods) {
            if(method instanceof Endpoint) {
                Endpoint endpoint = (Endpoint) method;
                newMethods.add(new Method(method));

                StringBuilder queryParams = new StringBuilder();
                for(edu.university.ecs.lab.common.models.ir.Parameter parameter : method.getParameters()) {
                    for(Annotation annotation : parameter.getAnnotations()) {
                        if(annotation.getName().equals("RequestParam")) {
                            queryParams.append("&");
                            if(annotation.getAttributes().containsKey("default")) {
                                queryParams.append(annotation.getAttributes().get("default"));
                            } else if(annotation.getAttributes().containsKey("name")) {
                                queryParams.append(annotation.getAttributes().get("name"));
                            } else {
                                queryParams.append(parameter.getName());
                            }

                            queryParams.append("={?}");
                        }
                    }
                }

                if(!queryParams.isEmpty()) {
                    queryParams.replace(0, 1, "?");
                }

                MethodCall methodCall = new MethodCall(abstractClass, "exchange", method.getLocation());
                methodCall.setCalledFrom(method.getName());
                methodCall.setObjectType("RestCallTemplate");
                methodCall.setObjectName("restCallTemplate");

                method.getMethodCalls().add(new RestCall(methodCall, endpoint.getUrl() + queryParams,
                        endpoint.getHttpMethod()));
            } else {
                newMethods.add(method);
            }
        }

        abstractClass.setClassRole(ClassRole.FEIGN_CLIENT);
        abstractClass.setFields(parseFields(abstractClass, cu.findAll(FieldDeclaration.class)));
        abstractClass.setAnnotations(parseAnnotations(abstractClass, cu.findAll(AnnotationExpr.class)));
        abstractClass.setMethods(newMethods);

        return abstractClass;
    }

    public static ConfigFile parseConfigurationFile(File file, Config config, Microservice microservice) {
        if(file.getName().endsWith(".yml")) {
            return NonJsonReadWriteUtils.readFromYaml(file.getPath(), config, microservice);
        } else if(file.getName().equals("DockerFile")) {
            return NonJsonReadWriteUtils.readFromDocker(file.toPath(), config, microservice);
        } else if(file.getName().equals("pom.xml")) {
            return NonJsonReadWriteUtils.readFromPom(file.toPath(), config, microservice);
        } else if (file.getName().equals("build.gradle")){
            return NonJsonReadWriteUtils.readFromGradle(file.toPath(), config, microservice);
        } else {
            return null;
        }
    }

    private static Set<AnnotationExpr> filterClassAnnotations() {
        Set<AnnotationExpr> classAnnotations = new HashSet<>();
        for (AnnotationExpr ae : cu.findAll(AnnotationExpr.class)) {
            if (ae.getParentNode().isPresent()) {
                Node n = ae.getParentNode().get();
                if (n instanceof ClassOrInterfaceDeclaration) {
                    classAnnotations.add(ae);
                }
            }
        }
        return classAnnotations;
    }

    /**
     * FeignClient represents an interface for making rest calls to a service
     * other than the current one. As such this method converts feignClient
     * interfaces into a service class whose methods simply contain the exact
     * rest call outlined by the interface annotations.
     *
     * @param classAnnotations
     * @return
     */
    private static AbstractClass handleRepositoryRestResource(Microservice microservice, AnnotationExpr requestMapping, Set<AnnotationExpr> classAnnotations) {
        AbstractClass abstractClass = prepareClass(microservice, path, packageName);

        if (abstractClass == null) {
            return null;
        }

        // Parse the methods
        Set<Method> methods = parseMethods(abstractClass, cu.findAll(MethodDeclaration.class), requestMapping);

        // New methods for conversion
        Set<Method> newEndpoints = new HashSet<>();
        // New rest calls for conversion
        List<MethodCall> newRestCalls = new ArrayList<>();

        // Arbitrary preURL naming scheme if not defined in the annotation
        String preURL = "/" + className.toLowerCase().replace("repository", "") + "s";

        for(AnnotationExpr annotation : classAnnotations) {
            if(annotation.getNameAsString().equals("RepositoryRestResource")) {
                if (requestMapping instanceof NormalAnnotationExpr) {
                    NormalAnnotationExpr nae = (NormalAnnotationExpr) requestMapping;
                    for (MemberValuePair pair : nae.getPairs()) {
                        if (pair.getNameAsString().equals("path")) {
                            preURL += pair.getValue().toString();
                            break;
                        }
                    }
                } else if (requestMapping instanceof SingleMemberAnnotationExpr) {
                    preURL += annotation.asSingleMemberAnnotationExpr().getMemberValue().toString();
                    break;
                }
            }
        }

        // For each method that is detected as an endpoint convert into a Method + RestCall
        for(Method method : methods) {
            String url = "/search";
            boolean restResourceFound = false;
            boolean isExported = true;
            for(Annotation ae : method.getAnnotations()) {
                if (requestMapping instanceof NormalAnnotationExpr) {
                    NormalAnnotationExpr nae = (NormalAnnotationExpr) requestMapping;
                    for (MemberValuePair pair : nae.getPairs()) {
                        if (pair.getNameAsString().equals("path")) {
                            preURL = pair.getValue().toString();
                            restResourceFound = true;
                        } else if(pair.getNameAsString().equals("exported")) {
                            if(pair.getValue().toString().equals("false")) {
                                isExported = false;
                            }
                        }
                    }
                }
            }

            // This method not exported (exposed) as an Endpoint
            if(!isExported) {
                continue;
            }

            // If no restResource annotation found we use default /search url start
            if(!restResourceFound) {
                url += ("/" + method.getName());
            }

            Endpoint endpoint = new Endpoint(method, preURL + url, HttpMethod.GET);
            newEndpoints.add(endpoint);
        }

        // Build the JClass
        abstractClass.setClassRole(ClassRole.REP_REST_RSC);
        abstractClass.setFields(parseFields(abstractClass, cu.findAll(FieldDeclaration.class)));
        abstractClass.setAnnotations(parseAnnotations(abstractClass, cu.findAll(AnnotationExpr.class)));
        abstractClass.setMethods(newEndpoints);

        return abstractClass;
    }

    private static AbstractClass prepareClass(edu.university.ecs.lab.common.models.ir.Node parent, Path path, String packageName) {
        AbstractClass abstractClass = null;

        List<ClassOrInterfaceDeclaration> classInterfaceDecs = cu.findAll(ClassOrInterfaceDeclaration.class);
        List<EnumDeclaration> enumDecs = cu.findAll(EnumDeclaration.class);
        List<RecordDeclaration> recordDecs = cu.findAll(RecordDeclaration.class);

        if (!classInterfaceDecs.isEmpty()) {
            AccessModifier protection = AccessModifier.fromAccessSpecifier(classInterfaceDecs.get(0).getAccessSpecifier());
            Boolean isFinal = classInterfaceDecs.get(0).isFinal();
            Boolean isAbstract = classInterfaceDecs.get(0).isAbstract();
            Boolean isStatic = classInterfaceDecs.get(0).isStatic();

            if (!classInterfaceDecs.get(0).isInterface()) {
                List<String> extendedTypes = classInterfaceDecs.get(0).getExtendedTypes().stream().map(NodeWithSimpleName::getNameAsString).toList();
                String extendedType = "";
                if (!extendedTypes.isEmpty())
                    extendedType = extendedTypes.get(0);

                abstractClass = new JClass(parent, path, packageName, extendedType,
                        classInterfaceDecs.get(0).getImplementedTypes().stream().map(NodeWithSimpleName::getNameAsString).collect(Collectors.toSet()));
            } else {
                abstractClass = new JInterface(parent, path, packageName, classInterfaceDecs.get(0).getExtendedTypes().stream().map(NodeWithSimpleName::getNameAsString).collect(Collectors.toSet()));
            }

            abstractClass.setProtection(protection);
            abstractClass.setIsFinal(isFinal);
            abstractClass.setIsAbstract(isAbstract);
            abstractClass.setIsStatic(isStatic);

        } else if (!enumDecs.isEmpty()) {
            AccessModifier protection = AccessModifier.fromAccessSpecifier(enumDecs.get(0).getAccessSpecifier());

            Set<String> enumEntries = new HashSet<>();
            enumDecs.get(0).getEntries().forEach(entry -> enumEntries.add(entry.getNameAsString()));
            abstractClass = new JEnum(parent, path, packageName,
                    enumDecs.get(0).getImplementedTypes().stream().map(NodeWithSimpleName::getNameAsString).collect(Collectors.toSet()),
                    enumEntries);

            abstractClass.setProtection(protection);
        } else if (!recordDecs.isEmpty()) {
            AccessModifier protection = AccessModifier.fromAccessSpecifier(recordDecs.get(0).getAccessSpecifier());
            Boolean isStatic = recordDecs.get(0).isStatic();

            abstractClass = new JRecord(parent, path, packageName, recordDecs.get(0).getImplementedTypes().stream().map(NodeWithSimpleName::getNameAsString).collect(Collectors.toSet()));

            abstractClass.setProtection(protection);
            abstractClass.setIsStatic(isStatic);
        }

        return abstractClass;
    }

//    private static JClass handleJS(String filePath) throws IOException, InterruptedException {
//        JClass jClass = new JClass(filePath, filePath, "", ClassRole.FEIGN_CLIENT, new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>(), new ArrayList<>(), new HashSet<>(), new HashSet<>(), AccessModifier.PACKAGE_PRIVATE, false, false, false);
//
//        Set<RestCall> restCalls = new HashSet<>();
//        // Command to run Node.js script
//        ProcessBuilder processBuilder = new ProcessBuilder("node", "/scripts/parser.js");
//        Process process = processBuilder.start();
//
//        // Capture the output
//        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
//        String line;
//        while ((line = reader.readLine()) != null) {
//            String[] split = line.split(";");
//            restCalls.add(new RestCall(split[0], split[1], split[2], split[3], split[4], split[5], split[6], split[7]));
//        }
//
//        // Wait for the Node.js process to complete
//        process.waitFor();
//
//        return jClass;
//    }
}