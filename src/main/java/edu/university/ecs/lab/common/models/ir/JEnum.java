package edu.university.ecs.lab.common.models.ir;

import com.fasterxml.jackson.annotation.JsonTypeName;
import edu.university.ecs.lab.common.models.enums.AccessModifier;
import edu.university.ecs.lab.common.models.enums.ClassRole;
import edu.university.ecs.lab.common.models.enums.FileType;
import lombok.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@JsonTypeName("JEnum")
public class JEnum extends JClass {
    /**
     * A list of the constants defined in the Enum
     */
    private List<String> enumTypes;

    public JEnum(String name, String path, String packageName, ClassRole classRole) {
        super(name, path, packageName, classRole);
        this.fileType = FileType.JENUM;
    }

    public JEnum(String name, String path, String packageName, ClassRole classRole, Set<Import> imports, Set<Method> methods, Set<Field> fields, Set<Annotation> classAnnotations, List<MethodCall> methodCalls, Set<String> implementedTypes, AccessModifier protection, List<String> enumTypes) {
        super(name, path, packageName, classRole, imports, methods, fields, classAnnotations, methodCalls, implementedTypes, new HashSet<String>(), protection, true, false, true);
        this.fileType = FileType.JENUM;
        this.enumTypes = enumTypes;
    }
}
