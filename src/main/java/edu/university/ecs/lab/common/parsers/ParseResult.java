package edu.university.ecs.lab.common.parsers;

import edu.university.ecs.lab.common.models.ir.AbstractClass;
import java.util.List;

public class ParseResult {
    private final List<AbstractClass> classes;
    private final boolean success;
    private final String failureReason;

    private ParseResult(List<AbstractClass> classes, boolean success, String failureReason) {
        this.classes = List.copyOf(classes);
        this.success = success;
        this.failureReason = failureReason;
    }

    public static ParseResult success(AbstractClass abstractClass) {
        return success(abstractClass == null ? List.of() : List.of(abstractClass));
    }

    /** All containers extracted from one physical source file, without mutating the service. */
    public static ParseResult success(List<AbstractClass> classes) {
        return new ParseResult(classes, true, null);
    }

    public static ParseResult failure(String reason) {
        return new ParseResult(List.of(), false, reason);
    }

    /** Compatibility accessor for consumers that expect a single container. */
    public AbstractClass getAbstractClass() { return classes.isEmpty() ? null : classes.getFirst(); }
    public List<AbstractClass> getClasses() { return classes; }
    public boolean isSuccess() { return success; }
    public String getFailureReason() { return failureReason; }
}
