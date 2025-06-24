package edu.university.ecs.lab.common.models.ir;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.github.javaparser.ast.ImportDeclaration;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 *
 */
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(callSuper = true)
@JsonTypeName("Import")
public class Import extends Component {
    /**
     * String containing the package being imported
     */
    protected String importPackage;

    /**
     * String containing the object being imported from importPackage.
     * Will be an asterisk if all objects from a package are being imported.
     */
    protected String importObject;

    /**
     * Boolean indicating if the import was static or not
     */
    protected Boolean isStatic;

    /**
     * Creates a new Import model
     *
     */
    public Import(Node parent, ImportDeclaration importDeclaration) {
        super(parent, importDeclaration.getNameAsString(), new Location(importDeclaration.getRange().get()));

        if (importDeclaration.isAsterisk()) {
            this.importPackage = importDeclaration.getNameAsString();
            this.importObject = "*";

        } else {
            this.importPackage = importDeclaration.getNameAsString().substring(0, importDeclaration.getNameAsString().lastIndexOf("."));
            this.importObject = importDeclaration.getNameAsString().substring(importDeclaration.getNameAsString().lastIndexOf(".") + 1);

        }

        this.isStatic = importDeclaration.isStatic();
    }

    /**
     * Returns whether the import is for an entire package (i.e., com.package.*)
     * @return True if it imports a full package, false if otherwise
     */
    public Boolean importsEntirePackage() {
        return importObject.equals("*");
    }

    @Override
    public List<Component> getChildren() {
        return new ArrayList<>();
    }

    @Override
    public void clearDescendants() {}
}
