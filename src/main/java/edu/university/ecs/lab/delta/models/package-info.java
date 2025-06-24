/**
 * This package and subpackage {@link edu.university.ecs.lab.delta.models.enums} contains models used for representing changes between two commits in a microservice system.
 * <p>
 * It includes:
 *     - {@link edu.university.ecs.lab.delta.models.AbstractDelta}: Represents a single change between two commits.
 *     - {@link edu.university.ecs.lab.delta.models.ComponentDelta}: Represents a change for a specific {@link edu.university.ecs.lab.common.models.ir.Component}.
 *     - {@link edu.university.ecs.lab.delta.models.ModifyDelta}: Represents a modification to a set of Components in a file.
 *     - {@link edu.university.ecs.lab.delta.models.SimpleDelta}: Represents the addition or removal of a file.
 *     - {@link edu.university.ecs.lab.delta.models.SystemChange}: Represents the overall change in the Intermediate Representation (IR)
 *     from an old commit to a new commit.
 *     - {@link edu.university.ecs.lab.delta.models.enums.ChangeType}: Enumerates types of changes (ADD, MODIFY, DELETE).
 * </p>
 */
package edu.university.ecs.lab.delta.models;