package edu.university.ecs.lab.common.models.ir;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import edu.university.ecs.lab.common.models.enums.AccessModifier;
import edu.university.ecs.lab.common.models.enums.ClassRole;
import edu.university.ecs.lab.common.models.enums.ClassType;
import edu.university.ecs.lab.common.models.enums.FileType;
import lombok.*;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@NoArgsConstructor
@Setter
@Getter
@JsonTypeName("JInterface")
public class JInterface extends AbstractClass {
    /**
     * Other interfaces extended
     */
    @JsonDeserialize(as = HashSet.class)
    @NonNull
    private Set<String> extendedTypes;

    public JInterface(Node parent, Path path, String packageName, @NonNull Set<String> extendedTypes) {
        super(parent, path, ClassRole.UNKNOWN, ClassType.INTERFACE, packageName, AccessModifier.PACKAGE_PRIVATE, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE);
        this.extendedTypes = extendedTypes;
    }

    public JInterface(Node parent, Path path, ClassRole classRole, String packageName, AccessModifier accessModifier, Boolean isFinal, Set<Import> imports, Set<Annotation> annotations, Set<Field> fields, @NonNull Set<Method> methods, List<MethodCall> methodCalls, Set<String> extendedTypes) {
        super(parent, path, classRole, ClassType.INTERFACE, packageName, accessModifier, isFinal, Boolean.FALSE, Boolean.FALSE, imports, annotations, fields, methods, methodCalls);
        this.extendedTypes = extendedTypes;
    }
}
