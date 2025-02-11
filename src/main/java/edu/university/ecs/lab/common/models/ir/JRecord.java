package edu.university.ecs.lab.common.models.ir;

import edu.university.ecs.lab.common.models.enums.AccessModifier;
import edu.university.ecs.lab.common.models.enums.ClassRole;
import edu.university.ecs.lab.common.models.enums.FileType;

import java.util.List;
import java.util.Set;

public class JRecord extends JClass {
    public JRecord(String name, String path, String packageName, ClassRole classRole) {
        super(name, path, packageName, classRole);
        this.fileType = FileType.JRECORD;
    }

    public JRecord(String name, String path, String packageName, ClassRole classRole, Set<Import> imports, Set<Method> methods, Set<Field> fields, Set<Annotation> classAnnotations, List<MethodCall> methodCalls, Set<String> implementedTypes, AccessModifier protection) {
        super(name, path, packageName, classRole, imports, methods, fields, classAnnotations, methodCalls, implementedTypes, protection);
        this.fileType = FileType.JRECORD;
    }
}
