package edu.university.ecs.lab.common.models.ir;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import edu.university.ecs.lab.common.models.enums.AccessModifier;
import lombok.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Represents a field attribute in a Java class or in our case a JClass.
 */
@NoArgsConstructor
@Getter
@Setter
@JsonTypeName("Field")
@EqualsAndHashCode(callSuper = true)
public class Field extends Component {
    /**
     * Java class type of the class variable e.g. String
     */
    protected String fieldType;

    /**
     * The protection applied to this Field
     */
    protected AccessModifier protection;

    /**
     * Whether the field is static
     */
    protected Boolean isStatic;

    /**
     * Whether the field is final
     */
    protected Boolean isFinal;

    /**
     * The initializer of the field, if present
     */
    private String initializer;

    /**
     * Annotations on this field
     */
    @JsonDeserialize(as = HashSet.class)
    protected Set<Annotation> annotations;

    public Set<Annotation> getAnnotations() {
        if (this.annotations == null) {
            this.annotations = new HashSet<>();
        }
        return this.annotations;
    }

    public Field(Node parent, FieldDeclaration fd, VariableDeclarator vd) {
        super(parent, vd.getNameAsString(), new Location(vd.getRange().get()));

        this.fieldType = vd.getTypeAsString();
        this.protection = AccessModifier.fromAccessSpecifier(fd.getAccessSpecifier());
        this.isStatic = fd.isStatic();
        this.isFinal = fd.isFinal();
        
        this.initializer = "";
        Optional<Expression> optInitExpr = vd.getInitializer();
        if (optInitExpr.isPresent()) {
            this.initializer = optInitExpr.get().toString();
        }

        this.annotations = edu.university.ecs.lab.common.utils.SourceToObjectUtils.parseAnnotations(this, fd.getAnnotations());
    }

    @Override
    public List<Component> getChildren() {
        return new ArrayList<>(getAnnotations());
    }

    @Override
    public void clearDescendants() {
        setAnnotations(new HashSet<>());
    }
}
