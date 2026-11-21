package edu.university.ecs.lab.common.models.ir;

import com.fasterxml.jackson.annotation.JsonTypeName;
import edu.university.ecs.lab.common.models.enums.AccessModifier;
import edu.university.ecs.lab.common.models.enums.ClassRole;
import edu.university.ecs.lab.common.models.enums.ClassType;
import java.nio.file.Path;

/** A JavaScript/TypeScript source module, not a Java class or inferred deployable service. */
@JsonTypeName("JSModule")
public final class JSModule extends AbstractClass {
    public JSModule() { super(); }

    public JSModule(Node parent, Path path, ClassRole role, String componentName) {
        super(parent, path, role, ClassType.MODULE, componentName,
                AccessModifier.PUBLIC, false, false, false);
    }
}
