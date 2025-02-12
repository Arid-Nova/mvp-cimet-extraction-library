package edu.university.ecs.lab.common.models.ir;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.type.ReferenceType;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import edu.university.ecs.lab.common.models.enums.AccessModifier;
import edu.university.ecs.lab.common.models.serialization.JsonSerializable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.*;

/**
 * Represents a method declaration in Java.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class Method extends Node {
    /**
     * The protection level for this Method
     */
    protected AccessModifier protection;

    /**
     * Set of fields representing parameters
     */
    protected Set<Parameter> parameters;

    /**
     * Java return type of the method
     */
    protected String returnType;

    /**
     * The microservice id that this method belongs to
     */
    protected String microserviceName;

    /**
     * Method definition level annotations
     */
    protected Set<Annotation> annotations;

    /**
     * The class id that this method belongs to
     */
    protected String className;

    /**
     * Whether the function is abstract
     */
    private Boolean isAbstract;

    /**
     * Whether the function is static
     */
    private Boolean isStatic;

    /**
     * Whether the function is final
     */
    private Boolean isFinal;

    /**
     * A list of exceptions that the function can throw when called
     */
    private Set<String> thrownExceptions;

    public Method(String name, String packageAndClassName, Set<Parameter> parameters, String typeAsString, Set<Annotation> annotations, String microserviceName,
                  String className, AccessModifier protection, Boolean isAbstract, Boolean isStatic, Boolean isFinal, Set<String> thrownExceptions) {
        this.name = name;
        this.packageAndClassName = packageAndClassName;
        this.parameters = parameters;
        this.returnType = typeAsString;
        this.annotations = annotations;
        this.microserviceName = microserviceName;
        this.className = className;
        this.protection = protection;
        this.isAbstract = isAbstract;
        this.isStatic = isStatic;
        this.isFinal = isFinal;
        this.thrownExceptions = thrownExceptions;
    }

    public Method(MethodDeclaration methodDeclaration) {
        this.name = methodDeclaration.getNameAsString();
        this.packageAndClassName = methodDeclaration.getClass().getPackageName() + "." + methodDeclaration.getClass().getName();
        this.parameters = parseParameters(methodDeclaration.getParameters());
        this.protection = AccessModifier.fromAccessSpecifier(methodDeclaration.getAccessSpecifier());
        this.isAbstract = methodDeclaration.isAbstract();
        this.isStatic = methodDeclaration.isStatic();
        this.isFinal = methodDeclaration.isFinal();
        NodeList<ReferenceType> exceptions = methodDeclaration.getThrownExceptions();
        this.thrownExceptions = new HashSet<>();
        exceptions.forEach(exception -> this.thrownExceptions.add(exception.toString()));
    }

    /**
     * see {@link JsonSerializable#toJsonObject()}
     */
    @Override
    public JsonObject toJsonObject() {
        JsonObject jsonObject = new JsonObject();
        Gson gson = new Gson();

        jsonObject.addProperty("name", getName());
        jsonObject.addProperty("packageAndClassName", getPackageAndClassName());
        jsonObject.add("annotations", JsonSerializable.toJsonArray(getAnnotations()));
        jsonObject.add("parameters", JsonSerializable.toJsonArray(getParameters()));
        jsonObject.addProperty("returnType", getReturnType());
        jsonObject.addProperty("microserviceName", microserviceName);
        jsonObject.addProperty("className", className);
        jsonObject.addProperty("protection", protection.toString());
        jsonObject.addProperty("isAbstract", getIsAbstract());
        jsonObject.addProperty("isFinal", getIsFinal());
        jsonObject.addProperty("isStatic", getIsStatic());
        jsonObject.add("thrownExceptions", gson.toJsonTree(getThrownExceptions()).getAsJsonArray());

        return jsonObject;
    }

    /**
     * Get set of parameters from node list
     * 
     * @param parameters Node list of javaparser parameter objects
     * @return set of parameter objects
     */
    private Set<Parameter> parseParameters(NodeList<com.github.javaparser.ast.body.Parameter> parameters) {
        HashSet<Parameter> parameterSet = new HashSet<>();

        for(com.github.javaparser.ast.body.Parameter parameter : parameters) {
            parameterSet.add(new Parameter(parameter, getPackageAndClassName()));
        }


        return parameterSet;
    }

}
