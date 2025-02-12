package edu.university.ecs.lab.common.models.ir;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import edu.university.ecs.lab.common.models.enums.AccessModifier;
import edu.university.ecs.lab.common.models.enums.ClassRole;
import edu.university.ecs.lab.common.models.enums.FileType;
import edu.university.ecs.lab.common.models.serialization.JsonSerializable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Set;

@EqualsAndHashCode(callSuper = true)
public class JEnum extends JClass implements JsonSerializable {
    /**
     * A list of the constants defined in the Enum
     */
    @Getter
    @Setter
    private List<String> enumTypes;

    public JEnum(String name, String path, String packageName, ClassRole classRole) {
        super(name, path, packageName, classRole);
        this.fileType = FileType.JENUM;
    }

    public JEnum(String name, String path, String packageName, ClassRole classRole, Set<Import> imports, Set<Method> methods, Set<Field> fields, Set<Annotation> classAnnotations, List<MethodCall> methodCalls, Set<String> implementedTypes, AccessModifier protection, Boolean isFinal, List<String> enumTypes) {
        super(name, path, packageName, classRole, imports, methods, fields, classAnnotations, methodCalls, implementedTypes, protection, isFinal);
        this.fileType = FileType.JENUM;
        this.enumTypes = enumTypes;
    }

    /**
     * see {@link JsonSerializable#toJsonObject()}
     */
    @Override
    public JsonObject toJsonObject() {
        JsonObject jsonObject = super.toJsonObject();
        Gson gson = new Gson();

        jsonObject.add("enumTypes", gson.toJsonTree(getEnumTypes()).getAsJsonArray());

        return jsonObject;
    }
}
