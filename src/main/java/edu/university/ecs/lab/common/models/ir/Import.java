package edu.university.ecs.lab.common.models.ir;

import com.google.gson.JsonObject;
import edu.university.ecs.lab.common.models.serialization.JsonSerializable;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 *
 */
@Data
@EqualsAndHashCode
public class Import extends Node {
    /**
     * String containing the package being imported
     */
    private String importPackage;

    /**
     * String containing the object being imported from importPackage.
     * Will be an asterisk if all objects from a package are being imported.
     */
    private String importObject;

    /**
     * Boolean indicating if the import was static or not
     */
    private Boolean isStatic;

    /**
     * Back reference to parent JClass
     */
    private JClass parent;

    /**
     * Creates a new Import model
     *
     * @param importPackage the package from which importObject is being imported from
     * @param importObject the object(s) being imported from the importPackage
     * @param isStatic whether the import was static
     * @param packageAndClassName the package and class name where the import statement is listed
     */
    public Import(String importPackage, String importObject, Boolean isStatic, String packageAndClassName) {
        this.name = importPackage + "." + importObject;
        this.packageAndClassName = packageAndClassName;
        this.importPackage = importPackage;
        this.importObject = importObject;
        this.isStatic = isStatic;
    }

    /**
     * Set backward reference to the parent of this component
     * @param parent the parent of this component
     */
    public void setParent(JClass parent) {
        this.parent = parent;
    }

    /**
     * see {@link JsonSerializable#toJsonObject()}
     */
    @Override
    public JsonObject toJsonObject() {
        JsonObject jsonObject = new JsonObject();

        jsonObject.addProperty("name", getName());
        jsonObject.addProperty("packageAndClassName", getPackageAndClassName());
        jsonObject.addProperty("importPackage", getImportPackage());
        jsonObject.addProperty("importObject", getImportObject());
        jsonObject.addProperty("isStatic", getIsStatic());

        return jsonObject;
    }
}
