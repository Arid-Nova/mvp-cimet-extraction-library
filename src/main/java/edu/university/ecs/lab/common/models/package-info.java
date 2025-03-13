/**
 * Provides classes and sub-packages that represent various components of a microservice system
 * and facilitate configuration of these representations in JSON format.
 * <p>
 * This package includes:
 * - 
 *   - {@link edu.university.ecs.lab.common.models.enums}: Enumerations used for categorizing different components, such as Class Roles, HTTP Methods, etc.
 * - Other model classes representing key elements of the microservice system:
 *   - {@link edu.university.ecs.lab.common.models.ir.Annotation}: Represents annotations within classes.
 *     modeling microservice connections.
 *   - {@link edu.university.ecs.lab.common.models.ir.Endpoint}: Represents an endpoint exposed by a microservice.
 *   - {@link edu.university.ecs.lab.common.models.ir.Field}: Represents fields within classes.
 *   - {@link edu.university.ecs.lab.common.models.ir.Import}: Represents imports with classes.
 *   - {@link edu.university.ecs.lab.common.models.ir.JClass}: Represents a Java class within a microservice.
 *   - {@link edu.university.ecs.lab.common.models.ir.JEnum}: Represents a Java enum within a microservice.
 *   - {@link edu.university.ecs.lab.common.models.ir.JInterface}: Represents a Java interface within a microservice.
 *   - {@link edu.university.ecs.lab.common.models.ir.JRecord}: Represents a Java record within a microservice.
 *   - {@link edu.university.ecs.lab.common.models.ir.Method}: Represents a method within classes.
 *   - {@link edu.university.ecs.lab.common.models.ir.MethodCall}: Represents a method call within microservices.
 *   - {@link edu.university.ecs.lab.common.models.ir.Microservice}: Represents a microservice within the system,
 *     including its components like controllers, services, etc.
 *   - {@link edu.university.ecs.lab.common.models.ir.MicroserviceSystem}: Represents a microservice system and all its components,
 *     including the name of the system, the set of microservices, etc.
 *   - {@link edu.university.ecs.lab.common.models.ir.Parameter}: Represents a parameter within a function.
 *   - {@link edu.university.ecs.lab.common.models.ir.RestCall}: Represents a call to an endpoint mapping and exists at the service level
 *   - {@link edu.university.ecs.lab.common.models.ir.Node}: Represents a generic object that could be any Annotation, Endpoint, Field, etc.
 */
package edu.university.ecs.lab.common.models;