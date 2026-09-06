package edu.university.ecs.lab.common.parsers;

import edu.university.ecs.lab.common.models.ir.AbstractClass;

public class ParseResult {
    private final AbstractClass abstractClass;
    private final boolean success;
    private final String failureReason;

    private ParseResult(AbstractClass abstractClass, boolean success, String failureReason) {
        this.abstractClass = abstractClass;
        this.success = success;
        this.failureReason = failureReason;
    }

    public static ParseResult success(AbstractClass abstractClass) {
        return new ParseResult(abstractClass, true, null);
    }

    public static ParseResult failure(String reason) {
        return new ParseResult(null, false, reason);
    }

    public AbstractClass getAbstractClass() { return abstractClass; }
    public boolean isSuccess() { return success; }
    public String getFailureReason() { return failureReason; }
}
