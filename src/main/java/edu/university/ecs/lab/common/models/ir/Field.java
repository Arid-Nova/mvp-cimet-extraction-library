package edu.university.ecs.lab.common.models.ir;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import edu.university.ecs.lab.common.models.enums.AccessModifier;
import lombok.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
    }

    @Override
    public List<Component> getChildren() {
        return new ArrayList<>();
    }

    @Override
    public void clearDescendants() {}
}
