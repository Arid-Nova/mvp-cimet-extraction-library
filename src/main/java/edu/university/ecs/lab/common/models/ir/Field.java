package edu.university.ecs.lab.common.models.ir;

import com.fasterxml.jackson.annotation.JsonTypeName;
import edu.university.ecs.lab.common.models.enums.AccessModifier;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Represents a field attribute in a Java class or in our case a JClass.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode
@JsonTypeName("Field")
public class Field extends Component {
    /**
     * Java class type of the class variable e.g. String
     */
    private String fieldType;

    /**
     * The protection applied to this Field
     */
    private AccessModifier protection;

    /**
     * Whether the field is static
     */
    private Boolean isStatic;

    /**
     * Whether the field is final
     */
    private Boolean isFinal;

    /**
     * The initializer of the field, if present
     */
    private String initializer;

    public Field(String name, String packageAndClassName, String fieldType, AccessModifier protection, Boolean isStatic, Boolean isFinal, Location location, String initializer) {
        this.name = name;
        setPackageAndClassNames(packageAndClassName);
        this.fieldType = fieldType;
        this.protection = protection;
        this.isStatic = isStatic;
        this.isFinal = isFinal;
        this.location = location;
        this.initializer = initializer;
    }
}
