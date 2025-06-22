package edu.university.ecs.lab.common.models.ir;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

    public Parameter(Node parent, com.github.javaparser.ast.body.Parameter parameter) {
        super(parent, parameter.getNameAsString(), new Location(parameter.getRange().get()));


        this.name = parameter.getNameAsString();
        this.parameterType = parameter.getTypeAsString();
        this.annotations = parameter.getAnnotations().stream().map(annotationExpr -> new Annotation(this, annotationExpr)).collect(Collectors.toSet());
        this.isVariableParameter = parameter.isVarArgs();

        // Include some additional annotations for variable parameters
        if (this.isVariableParameter) {
            this.annotations.addAll(parameter.getVarArgsAnnotations().stream().map(annotationExpr ->
                            new Annotation(this, annotationExpr))
                    .collect(Collectors.toSet()));
        }

        if(parameter.getRange().isPresent())
            this.location = new Location(parameter.getRange().get());
        else
            this.location = null;
    }

    @Override
    public List<Component> getChildren() {
        return new ArrayList<>(getAnnotations());
    }
}
