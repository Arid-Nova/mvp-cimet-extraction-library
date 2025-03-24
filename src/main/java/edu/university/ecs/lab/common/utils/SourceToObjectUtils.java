package edu.university.ecs.lab.common.utils;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.type.ReferenceType;
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
import java.util.*;
import java.util.stream.Collectors;

/**
 * Static utility class for parsing a file and returning associated models from code structure.
 */
public class SourceToObjectUtils {
    private static CompilationUnit cu;
    private static String microserviceName = "";
    private static String path;
    private static String className;
    private static String packageName;
    private static String packageAndClassName;
    private static CombinedTypeSolver combinedTypeSolver;
    private static Config config;


    private static void generateStaticValues(File sourceFile, Config config1) {
        // Parse the highest level node being compilation unit
        config = config1;
        try {
            cu = StaticJavaParser.parse(sourceFile);
        } catch (Exception e) {
            microserviceName = "";
            return;
        }
        if (!cu.findAll(PackageDeclaration.class).isEmpty()) {
            packageName = cu.findAll(PackageDeclaration.class).get(0).getNameAsString();
            packageAndClassName = packageName + "." + sourceFile.getName().replace(".java", "");
        }
        path = FileUtils.localPathToGitPath(sourceFile.getPath(), config.getRepoName());

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
     * This method parses a Java class file and return a JClass object.
     *
     * @param sourceFile the file to parse
     * @return the JClass object representing the file
     */
    public static JClass parseClass(File sourceFile, Config config, String microserviceName) {
        // Guard condition
        if(Objects.isNull(sourceFile) || FileUtils.isConfigurationFile(sourceFile.getPath())) {
            return null;
        }

        generateStaticValues(sourceFile, config);
        if (!microserviceName.isEmpty()) {
            SourceToObjectUtils.microserviceName = microserviceName;
        }

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
        if (classRole.equals(ClassRole.UNKNOWN)) {
            return null;
        }

        JClass jClass = null;
        if(classRole == ClassRole.FEIGN_CLIENT) {
            jClass = handleFeignClient(requestMapping, classAnnotations);
        } else if(classRole == ClassRole.REP_REST_RSC) {
            jClass = handleRepositoryRestResource(requestMapping, classAnnotations);
        } else {
            jClass = buildJClass(className, path, packageName, classRole,
                    parseImports(cu.findAll(ImportDeclaration.class)),
                    parseMethods(cu.findAll(MethodDeclaration.class), requestMapping),
                    parseFields(cu.findAll(FieldDeclaration.class)),
                    parseAnnotations(classAnnotations),
                    parseMethodCalls(cu.findAll(MethodDeclaration.class)));
        }

        // Build the JClass
        return jClass;

    }

    /**
     * This method parses importDeclarations list and returns a Set of Import models
     *
     * @param importDeclarations the list of importDeclarations to be parsed
     * @return a set of Import models representing the ImportDeclarations
     */
    public static Set<Import> parseImports(List<ImportDeclaration> importDeclarations) {
        HashSet<Import> imports = new HashSet<>();

        for (ImportDeclaration impDec : importDeclarations) {
            if (!impDec.isAsterisk()) {
                String fullImport = impDec.getNameAsString();

                String impPackage = fullImport.substring(0, fullImport.lastIndexOf("."));
                String impObject = fullImport.substring(fullImport.lastIndexOf(".") + 1);

                Location range = null;
                if (impDec.getRange().isPresent()) range = new Location(impDec.getRange().get());

                Import imp = new Import(impPackage, impObject, impDec.isStatic(), packageAndClassName, range);
                imports.add(imp);
            } else {
                String impPackage = impDec.getNameAsString();
                String impObject = "*";

                Location range = null;
                if (impDec.getRange().isPresent()) range = new Location(impDec.getRange().get());

                Import imp = new Import(impPackage, impObject, impDec.isStatic(), packageAndClassName, range);
                imports.add(imp);
            }
        }
        return imports;
    }

    /**
     * This method parses methodDeclarations list and returns a Set of Method models
     *
     * @param methodDeclarations the list of methodDeclarations to be parsed
     * @return a set of Method models representing the MethodDeclarations
     */
    public static Set<Method> parseMethods(List<MethodDeclaration> methodDeclarations, AnnotationExpr requestMapping) {
        // Get params and returnType
        Set<Method> methods = new HashSet<>();

        for (MethodDeclaration methodDeclaration : methodDeclarations) {
            Set<edu.university.ecs.lab.common.models.ir.Parameter> parameters = new HashSet<>();
            for (Parameter parameter : methodDeclaration.getParameters()) {
                parameters.add(new edu.university.ecs.lab.common.models.ir.Parameter(parameter, packageAndClassName));
            }

            NodeList<ReferenceType> exceptions = methodDeclaration.getThrownExceptions();
            Set<String> thrownExceptions = new HashSet<>();
            exceptions.forEach(exception -> thrownExceptions.add(exception.toString()));

            Location range = null;
            if (methodDeclaration.getRange().isPresent()) range = new Location(methodDeclaration.getRange().get());

            Method method = new Method(
                    methodDeclaration.getNameAsString(),
                    packageAndClassName,
                    parameters,
                    methodDeclaration.getTypeAsString(),
                    parseAnnotations(methodDeclaration.getAnnotations()),
                    microserviceName,
                    className,
                    AccessModifier.fromAccessSpecifier(methodDeclaration.getAccessSpecifier()),
                    methodDeclaration.isAbstract(),
                    methodDeclaration.isStatic(),
                    methodDeclaration.isFinal(),
                    thrownExceptions,
                    range);

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
     * This method parses methodDeclarations list and returns a Set of MethodCall models
     *
     * @param methodDeclarations the list of methodDeclarations to be parsed
     * @return a set of MethodCall models representing MethodCallExpressions found in the MethodDeclarations
     */
    public static List<MethodCall> parseMethodCalls(List<MethodDeclaration> methodDeclarations) {
        List<MethodCall> methodCalls = new ArrayList<>();

        // loop through method calls
        for (MethodDeclaration methodDeclaration : methodDeclarations) {
            for (MethodCallExpr mce : methodDeclaration.findAll(MethodCallExpr.class)) {
                String methodName = mce.getNameAsString();

                String calledServiceName = getCallingObjectName(mce);
                String calledServiceType = getCallingObjectType(mce);

                String parameterContents = mce.getArguments().stream().map(Objects::toString).collect(Collectors.joining(","));

                if (Objects.nonNull(calledServiceName)) {
                    Location range = null;
                    if (mce.getRange().isPresent()) range = new Location(mce.getRange().get());

                    MethodCall methodCall = new MethodCall(methodName, packageAndClassName, calledServiceType, calledServiceName,
                            methodDeclaration.getNameAsString(), parameterContents, microserviceName, className, range);

                    methodCall = convertValidRestCalls(mce, methodCall);

                    methodCalls.add(methodCall);
                }
            }
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
    private static Set<Field> parseFields(List<FieldDeclaration> fieldDeclarations) {
        Set<Field> javaFields = new HashSet<>();

        // loop through class declarations
        for (FieldDeclaration fd : fieldDeclarations) {
            for (VariableDeclarator variable : fd.getVariables()) {
                Location range = null;
                if (variable.getRange().isPresent()) range = new Location(variable.getRange().get());

                String init = "";
                Optional<Expression> optInitExpr = variable.getInitializer();
                if (optInitExpr.isPresent()) {
                    init = optInitExpr.get().toString();
                }

                javaFields.add(new Field(variable.getNameAsString(), packageAndClassName, variable.getTypeAsString(),
                        AccessModifier.fromAccessSpecifier(fd.getAccessSpecifier()),
                        fd.isStatic(), fd.isFinal(), range, init));
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
    private static Set<Annotation> parseAnnotations(Iterable<AnnotationExpr> annotationExprs) {
        Set<Annotation> annotations = new HashSet<>();

        for (AnnotationExpr ae : annotationExprs) {
            Location range = null;
            if (ae.getRange().isPresent()) range = new Location(ae.getRange().get());

            annotations.add(new Annotation(ae, packageAndClassName, range));
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
    private static JClass handleFeignClient(AnnotationExpr requestMapping, Set<AnnotationExpr> classAnnotations) {

        // Parse the methods
        Set<Method> methods = parseMethods(cu.findAll(MethodDeclaration.class), requestMapping);

        // New methods for conversion
        Set<Method> newMethods = new HashSet<>();
        // New rest calls for conversion
        List<MethodCall> newRestCalls = new ArrayList<>();

        // For each method that is detected as an endpoint convert into a Method + RestCall
        for(Method method : methods) {
            if(method instanceof Endpoint) {
                Endpoint endpoint = (Endpoint) method;
                newMethods.add(new Method(method.getName(), packageAndClassName, method.getParameters(),
                        method.getReturnType(), method.getAnnotations(), method.getMicroserviceName(),
                        method.getClassName(), method.getProtection(), method.isAbstract(), method.isStatic(),
                        method.isFinal(), method.getThrownExceptions(), method.getLocation()));

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

                newRestCalls.add(new RestCall(new MethodCall("exchange", packageAndClassName, "RestCallTemplate",
                        "restCallTemplate", method.getName(), "", endpoint.getMicroserviceName(),
                        endpoint.getClassName(), method.getLocation()), endpoint.getUrl() + queryParams,
                        endpoint.getHttpMethod()));
            } else {
                newMethods.add(method);
            }
        }


        // Build the JClass
        return buildJClass(
                className,
                path,
                packageName,
                ClassRole.FEIGN_CLIENT,
                parseImports(cu.findAll(ImportDeclaration.class)),
                newMethods,
                parseFields(cu.findAll(FieldDeclaration.class)),
                parseAnnotations(classAnnotations),
                newRestCalls);
    }

    public static ConfigFile parseConfigurationFile(File file, Config config) {
        if(file.getName().endsWith(".yml")) {
            return NonJsonReadWriteUtils.readFromYaml(file.getPath(), config);
        } else if(file.getName().equals("DockerFile")) {
            return NonJsonReadWriteUtils.readFromDocker(file.getPath(), config);
        } else if(file.getName().equals("pom.xml")) {
            return NonJsonReadWriteUtils.readFromPom(file.getPath(), config);
        } else if (file.getName().equals("build.gradle")){
            return NonJsonReadWriteUtils.readFromGradle(file.getPath(), config);
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
    private static JClass handleRepositoryRestResource(AnnotationExpr requestMapping, Set<AnnotationExpr> classAnnotations) {

        // Parse the methods
        Set<Method> methods = parseMethods(cu.findAll(MethodDeclaration.class), requestMapping);

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
        return buildJClass(
                className,
                path,
                packageName,
                ClassRole.REP_REST_RSC,
                parseImports(cu.findAll(ImportDeclaration.class)),
                newEndpoints,
                parseFields(cu.findAll(FieldDeclaration.class)),
                parseAnnotations(classAnnotations),
                newRestCalls);
    }

    private static JClass buildJClass(String name, String path, String packageName, ClassRole classRole, Set<Import> imports, Set<Method> methods, Set<Field> fields, Set<Annotation> classAnnotations, List<MethodCall> methodCalls) {
        JClass jClass = null;

        List<ClassOrInterfaceDeclaration> classInterfaceDecs = cu.findAll(ClassOrInterfaceDeclaration.class);
        List<EnumDeclaration> enumDecs = cu.findAll(EnumDeclaration.class);
        List<RecordDeclaration> recordDecs = cu.findAll(RecordDeclaration.class);
        if (!classInterfaceDecs.isEmpty()) {
            AccessModifier protection = AccessModifier.fromAccessSpecifier(classInterfaceDecs.get(0).getAccessSpecifier());
            Boolean isFinal = classInterfaceDecs.get(0).isFinal();
            Boolean isAbstract = classInterfaceDecs.get(0).isAbstract();
            Boolean isStatic = classInterfaceDecs.get(0).isStatic();

            if (!classInterfaceDecs.get(0).isInterface()) {
                jClass = new JClass(name, path, packageName, classRole, imports, methods, fields, classAnnotations, methodCalls,
                        classInterfaceDecs.get(0).getImplementedTypes().stream().map(NodeWithSimpleName::getNameAsString).collect(Collectors.toSet()),
                        classInterfaceDecs.get(0).getExtendedTypes().stream().map(NodeWithSimpleName::getNameAsString).collect(Collectors.toSet()),
                        protection, isFinal, isAbstract, isStatic);
            } else {
                jClass = new JInterface(name, path, packageName, classRole, imports, methods, fields, classAnnotations, methodCalls,
                        classInterfaceDecs.get(0).getExtendedTypes().stream().map(NodeWithSimpleName::getNameAsString).collect(Collectors.toSet()),
                        protection, isFinal, isStatic);

            }
        } else if (!enumDecs.isEmpty()) {
            AccessModifier protection = AccessModifier.fromAccessSpecifier(enumDecs.get(0).getAccessSpecifier());

            List<String> enumEntries = new ArrayList<>();
            enumDecs.get(0).getEntries().forEach(entry -> enumEntries.add(entry.getNameAsString()));
            jClass = new JEnum(name, path, packageName, classRole, imports, methods, fields, classAnnotations, methodCalls,
                    enumDecs.get(0).getImplementedTypes().stream().map(NodeWithSimpleName::getNameAsString).collect(Collectors.toSet()),
                    protection, enumEntries);
        } else if (!recordDecs.isEmpty()) {
            AccessModifier protection = AccessModifier.fromAccessSpecifier(recordDecs.get(0).getAccessSpecifier());
            Boolean isStatic = recordDecs.get(0).isStatic();

            jClass = new JRecord(name, path, packageName, classRole, imports, methods, fields, classAnnotations, methodCalls,
                    recordDecs.get(0).getImplementedTypes().stream().map(NodeWithSimpleName::getNameAsString).collect(Collectors.toSet()),
                    protection, isStatic);
        }

        return jClass;
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