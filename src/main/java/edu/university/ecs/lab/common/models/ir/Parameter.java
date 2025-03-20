package edu.university.ecs.lab.common.models.ir;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Represents a method call parameter
 */
@EqualsAndHashCode(callSuper = true)
@Data
@NoArgsConstructor
@JsonTypeName("Parameter")
public class Parameter extends Component {

    /**
     * Java class type of the class variable e.g. String
     */
    private String parameterType;

    @JsonDeserialize(as = HashSet.class)
    private Set<Annotation> annotations;

    private Boolean isVariableParameter;


    public Parameter(String name, String packageAndClassName, String parameterType, Set<Annotation> annotations, Boolean isVariableParameter, Location location) {
        this.name = name;
        setPackageAndClassNames(packageAndClassName);
        this.parameterType = parameterType;
        this.annotations = annotations;
        this.isVariableParameter = isVariableParameter;
        this.location = location;

        // Fill back references
        this.annotations.forEach(ann -> ann.setParent(this));
    }

    public Parameter(com.github.javaparser.ast.body.Parameter parameter, String packageAndClassName) {
        this.name = parameter.getNameAsString();
        this.parameterType = parameter.getTypeAsString();
        setPackageAndClassNames(packageAndClassName);
        this.annotations = parameter.getAnnotations().stream().map(annotationExpr -> new Annotation(annotationExpr,
                packageAndClassName, new Location(annotationExpr.getRange().get()))).collect(Collectors.toSet());
        this.isVariableParameter = parameter.isVarArgs();

        // Include some additional annotations for variable parameters
        if (this.isVariableParameter) {
            this.annotations.addAll(parameter.getVarArgsAnnotations().stream().map(annotationExpr ->
                    new Annotation(annotationExpr, packageAndClassName, new Location(annotationExpr.getRange().get())))
                    .collect(Collectors.toSet()));
        }

        if(parameter.getRange().isPresent())
            this.location = new Location(parameter.getRange().get());
        else
            this.location = null;
    }
}
