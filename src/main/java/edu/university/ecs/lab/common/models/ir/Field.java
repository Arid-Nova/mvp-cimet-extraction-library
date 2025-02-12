package edu.university.ecs.lab.common.models.ir;

import com.google.gson.JsonObject;
import edu.university.ecs.lab.common.models.enums.AccessModifier;
import edu.university.ecs.lab.common.models.serialization.JsonSerializable;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Represents a field attribute in a Java class or in our case a JClass.
 */
@Data
@EqualsAndHashCode
public class Field extends Node {
    /**
     * Java class type of the class variable e.g. String
     */
    private String type;

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

    public Field(String name, String packageAndClassName, String type, AccessModifier protection, Boolean isStatic, Boolean isFinal) {
        this.name = name;
        this.packageAndClassName = packageAndClassName;
        this.type = type;
        this.protection = protection;
        this.isStatic = isStatic;
        this.isFinal = isFinal;
    }


    /**
     * see {@link JsonSerializable#toJsonObject()}
     */
    @Override
    public JsonObject toJsonObject() {
        JsonObject jsonObject = new JsonObject();

        jsonObject.addProperty("name", getName());
        jsonObject.addProperty("packageAndClassName", getPackageAndClassName());
        jsonObject.addProperty("type", getType());
        jsonObject.addProperty("protection", getProtection().toString());
        jsonObject.addProperty("isStatic", getIsStatic());
        jsonObject.addProperty("isFinal", getIsFinal());

        return jsonObject;
    }
}
