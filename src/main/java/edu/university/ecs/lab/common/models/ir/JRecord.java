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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@NoArgsConstructor
@Getter
@Setter
@JsonTypeName("JRecord")
public class JRecord extends AbstractClass {
    /**
     * Class implementations
     */
    @JsonDeserialize(as = HashSet.class)
    @NonNull
    private Set<String> implementedTypes;

    public JRecord(Node parent, Path path, String packageName, @NonNull Set<String> implementedTypes) {
        super(parent, path, ClassRole.UNKNOWN, ClassType.RECORD, packageName, AccessModifier.PACKAGE_PRIVATE, Boolean.TRUE, Boolean.FALSE, Boolean.FALSE);
        this.implementedTypes = implementedTypes;
    }

    public JRecord(Node parent, Path path, ClassRole classRole, String packageName, AccessModifier accessModifier, Boolean isStatic, Set<Import> imports, Set<Annotation> annotations, Set<Field> fields, Set<Method> methods, Set<String> implementedTypes) {
        super(parent, path, classRole, ClassType.RECORD, packageName, accessModifier, Boolean.TRUE, isStatic, Boolean.FALSE, imports, annotations, fields, methods);
        this.implementedTypes = new HashSet<>(implementedTypes);
    }
}
