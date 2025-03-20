package edu.university.ecs.lab.common.models.enums;

import com.github.javaparser.ast.AccessSpecifier;

public enum AccessModifier {
    PUBLIC,
    PROTECTED,
    PRIVATE,
    PACKAGE_PRIVATE;

    /**
     * Converts the AccessSpecifier parser enum to our AccessModifier enum
     * @param as The AccessSpecifier for a component
     * @return An AccessModifier for a component
     */
    public static AccessModifier fromAccessSpecifier(AccessSpecifier as) {
        if (as.equals(AccessSpecifier.PUBLIC))
            return PUBLIC;
        else if (as.equals(AccessSpecifier.PROTECTED))
            return PROTECTED;
        else if (as.equals(AccessSpecifier.PRIVATE))
            return PRIVATE;
        else
            return PACKAGE_PRIVATE;
    }
}
