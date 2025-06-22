package edu.university.ecs.lab.common.models.ir;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import edu.university.ecs.lab.common.models.enums.AccessModifier;
import edu.university.ecs.lab.common.models.enums.ClassRole;
import edu.university.ecs.lab.common.models.enums.ClassType;
import edu.university.ecs.lab.common.models.enums.FileType;
import lombok.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@NoArgsConstructor
@Getter
@Setter
@JsonTypeName("JEnum")
public class JEnum extends AbstractClass {
    /**
     * Class implementations
     */
    @JsonDeserialize(as = HashSet.class)
    @NonNull
    private Set<String> implementedTypes;

    /**
     * A list of the constants defined in the Enum
     */
    @JsonDeserialize(as = HashSet.class)
    @NonNull
    private Set<String> enumTypes;

    public JEnum(Node parent, Path path, String packageName, @NonNull Set<String> implementedTypes, @NonNull Set<String> enumTypes) {
        super(parent, path, ClassRole.UNKNOWN, ClassType.ENUM, packageName, AccessModifier.PACKAGE_PRIVATE, Boolean.TRUE, Boolean.TRUE, Boolean.FALSE);

        this.implementedTypes = implementedTypes;
        this.enumTypes = enumTypes;
    }

    public JEnum(Node parent, Path path, ClassRole classRole, String packageName, AccessModifier accessModifier, Set<Import> imports, Set<Annotation> annotations, Set<Field> fields, Set<Method> methods, List<MethodCall> methodCalls, Set<String> implementedTypes, @NonNull Set<String> enumTypes) {
        super(parent, path, classRole, ClassType.ENUM, packageName, accessModifier, Boolean.TRUE, Boolean.TRUE, Boolean.FALSE, imports, annotations, fields, methods, methodCalls);

        this.implementedTypes = new HashSet<>(implementedTypes);
        this.enumTypes = new HashSet<>(enumTypes);
    }
}
