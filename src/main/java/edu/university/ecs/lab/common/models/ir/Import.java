package edu.university.ecs.lab.common.models.ir;

import com.fasterxml.jackson.annotation.JsonTypeName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 *
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@JsonTypeName("Import")
public class Import extends Component {
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
     * Creates a new Import model
     *
     * @param importPackage the package from which importObject is being imported from
     * @param importObject the object(s) being imported from the importPackage
     * @param isStatic whether the import was static
     * @param packageAndClassName the package and class name where the import statement is listed
     */
    public Import(String importPackage, String importObject, Boolean isStatic, String packageAndClassName, Location location) {
        this.name = importPackage + "." + importObject;
        setPackageAndClassNames(packageAndClassName);
        this.importPackage = importPackage;
        this.importObject = importObject;
        this.isStatic = isStatic;
        this.location = location;
    }

    /**
     * Returns whether the import is for an entire package (i.e., com.package.*)
     * @return True if it imports a full package, false if otherwise
     */
    public Boolean importsEntirePackage() {
        return importObject.equals("*");
    }
}
