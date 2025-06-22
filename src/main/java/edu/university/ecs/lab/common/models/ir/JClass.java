package edu.university.ecs.lab.common.models.ir;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import edu.university.ecs.lab.common.models.enums.AccessModifier;
import edu.university.ecs.lab.common.models.enums.ClassRole;
import edu.university.ecs.lab.common.models.enums.ClassType;
import edu.university.ecs.lab.common.models.enums.FileType;
import lombok.*;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Represents a class in Java. It holds all information regarding that class including all method
 * declarations, method calls, fields, etc.
 */
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(callSuper = true)
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type"
)
public class JClass extends AbstractClass {
    /**
     * Class extended
     */
    @Nullable
    protected String extendedType;

    /**
     * Class implementations
     */
    @JsonDeserialize(as = HashSet.class)
    @NonNull
    private Set<String> implementedTypes;

    public JClass(Node parent, Path path, String packageName, @NonNull String extendedType, @NonNull Set<String> implementedTypes) {
        super(parent, path, ClassRole.UNKNOWN, ClassType.CLASS, packageName, AccessModifier.PACKAGE_PRIVATE, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE);

        this.extendedType = extendedType;
        this.implementedTypes = implementedTypes;
    }

    public JClass(Node parent, Path path, ClassRole classRole, String packageName, AccessModifier accessModifier, Boolean isFinal, Boolean isStatic, Boolean isAbstract, Set<Import> imports, Set<Annotation> annotations, Set<Field> fields, Set<Method> methods, List<MethodCall> methodCalls, String extendedType, Set<String> implementedTypes) {
        super(parent, path, classRole, ClassType.CLASS, packageName, accessModifier, isFinal, isStatic, isAbstract, imports, annotations, fields, methods, methodCalls);

        this.extendedType = extendedType;
        this.implementedTypes = implementedTypes;
    }
}
