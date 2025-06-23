package edu.university.ecs.lab.common.models.ir;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.github.javaparser.ast.expr.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Represents an annotation in Java
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@JsonTypeName("Annotation")
public class Annotation extends Component {

    private Map<String, String> attributes;

    public Annotation(Node parent, AnnotationExpr annotationExpr) {
        super(parent, annotationExpr.getNameAsString(), new Location(annotationExpr.getRange().get()));

        this.attributes = parseAttributes(annotationExpr);
    }

    /**
     * Get contents of annotation object
     * 
     * @return comma-delimmited list of annotation content key-value pairs
     */
    @JsonIgnore
    public String getContents() {
        return getAttributes().entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).collect(Collectors.joining(","));
    }

    /**
     * Map attributes from annotation expression
     *
     * @param annotationExpr annotation expression object to parse
     * @return map of annotation attributes and their values
     */
    private static HashMap<String, String> parseAttributes(AnnotationExpr annotationExpr) {
        HashMap<String, String> attributes = new HashMap<>();

        if(annotationExpr instanceof MarkerAnnotationExpr) {
            return attributes;
        } else if (annotationExpr instanceof SingleMemberAnnotationExpr smAnnotationExpr) {
            if(smAnnotationExpr.getMemberValue() instanceof StringLiteralExpr sle) {
                attributes.put("default", sle.asString());
            } else {
                return attributes;
            }
        } else if (annotationExpr instanceof NormalAnnotationExpr nAnnotationExpr) {
            for(MemberValuePair mvp : nAnnotationExpr.getPairs()) {
                if(mvp.getValue() instanceof StringLiteralExpr sle) {
                    attributes.put(mvp.getNameAsString(), sle.asString());
                } else {
                    attributes.put(mvp.getNameAsString(), mvp.getValue().toString());
                }
            }
        }

        return attributes;
    }

    @Override
    public List<Component> getChildren() {
        return new ArrayList<>();
    }

    @Override
    public void clearDescendants() {}
}
