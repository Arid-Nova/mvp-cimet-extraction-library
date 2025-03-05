# CIMET Extraction Library

This Maven library props up the functionality of CIMET2. 
It is intended to be used as a temporal parser of microservice systems.
It is capable of extracting intermediate representations (IR) of the system
and delta representations of the changes to the system.

## Prerequisites

* Maven 3.6+
* Java 16+ (16 Recommended)

## To Compile:
    ``mvn clean install -DskipTests``

## Extracting an Intermediate Representation:
- Create a configuration file (see below)
- Import `edu.university.ecs.lab.intermediate.create.services.IRExtractionService` in your code.
- Use `IRExtractionService.create(configPath)` where configPath is a path to your configuration file to
create an IR.
- Use `IRExtractionService.createAndWrite(configPath, outputPath)` to create and write an IR to outputPath.
- Use `IRExtractionService.read(fPath)` to read an IR from a JSON file.

Sample input config file:

```json
{
  "systemName": "Train-ticket",
  "repositoryURL": "https://github.com/g-goulis/train-ticket-microservices-test.git",
  "endCommit": "06f3e1efe2e2539d05d91b0699cc8d9fe7be29d7",
  "baseBranch": "main"
}
```

Sample output produced (See `/docs` or Generating a JSON Schema for a full output schema):
```json
{
  "name": "Train-ticket",
  "commitID": "1.0",
  "microservices": [
    {
      "name": "ts-rebook-service",
      "path": ".\\clone\\train-ticket-microservices-test\\ts-rebook-service",
      "controllers": [
        {
          "packageName": "com.cloudhubs.trainticket.rebook.controller",
          "name": "WaitListOrderController.java",
          "path": ".\\clone\\train-ticket-microservices-test\\ts-rebook-service\\src\\main\\java\\com\\cloudhubs\\trainticket\\rebook\\controller\\WaitListOrderController.java",
          "classRole": "CONTROLLER",
          "annotations": [
            {
              "name": "RequestMapping",
              "contents": "\"/api/v1/waitorderservice\""
            },
            ...
          ],
          "fields": [
            {
              "name": "waitListOrderService",
              "type": "WaitListOrderService"
            },
            ...
          ],
          "methods": [
            {
              "name": "getAllOrders",
              "annotations": [
                {
                  "name": "GetMapping",
                  "contents": "[path \u003d \"/orders\"]"
                }
              ],
              "parameters": [
                {
                  "name": "HttpHeaders",
                  "type": "headers"
                }
              ],
              "returnType": "HttpEntity",
              "url": "/api/v1/waitorderservice/orders",
              "httpMethod": "GET",
              "microserviceName": "ts-rebook-service"
            },
            ...
          ],
          "methodCalls": [
            {
              "name": "info",
              "objectName": "LOGGER",
              "calledFrom": "getWaitListOrders",
              "parameterContents": "\"[getWaitListOrders][Get All Wait List Orders]\""
            },
            ...
          ]
        },
        ...
      ],
      "Services": [...],
      "Repositories": [...],
      "Entities": [...],
    ],
    "orphans": [...]
}
```

## Extracting a Delta Change Impact:
- Create a configuration file (see above)
- Import `edu.university.ecs.lab.delta.services.DeltaExtractionService` in your code.
- Use `DeltaExtractionService.create(configPath, oldCommit, newCommit)` where configPath is a path to your configuration file to
  create an IR, and oldCommit and newCommit are the two commits you want to create a SystemChange (set of Deltas) between.
- Use `DeltaExtractionService.createAndWrite(configPath, oldCommit, newCommit, outputPath)` to create and write a SystemChange (set of Deltas) to outputPath.
- Use `DeltaExtractionService.read(fPath)` to read a SystemChange (set of Deltas) from a JSON file.

Sample output produced (See `/docs` or Generating a JSON Schema for a full output schema):
```json
{
  "oldCommit": "06f3e1efe2e2539d05d91b0699cc8d9fe7be29d7",
  "newCommit": "82949fa07dcf82f66641f5807d629d15bab663a6",
  "changes": [
    {
      "oldPath": ".\\clone\\train-ticket-microservices-test\\ts-price-service\\src\\main\\java\\com\\cloudhubs\\trainticket\\price\\controller\\PriceController.java",
      "newPath": ".\\clone\\train-ticket-microservices-test\\ts-price-service\\src\\main\\java\\com\\cloudhubs\\trainticket\\price\\controller\\PriceController.java",
      "changeType": "MODIFY",
      "classChange": {}
    },
    ...
  ]
}
```

## Merging an IR & System Change:
- Create a configuration file (see above)
- Import `edu.university.ecs.lab.intermediate.merge.services.MergeService` in your code.
- Use `MergeService.create(configPath, intermediatePath, deltaPath, newCommitID)` where configPath is a path to your configuration file to
  create an IR, and intermediatePath is a path for the IR you want to apply the SystemChange at deltaPath to in order to create a new commit with newCommitID.
- Use `MergeService.createAndWrite(configPath, intermediatePath, deltaPath, newCommitID, outputPath)` to write a new IR with the SystemChange applied.

## Documentation
### Javadocs
You can build the latest javadocs by cloning the repository and running `mvn javadoc:javadoc`.

### Generating a JSON Schema
You can generate JSON schemas for the IR and SystemChange:
- Import `edu.university.ecs.lab.common.services.JsonSchemaService` in your code.
- Use `JsonSchemaService.writeSchemas()` to generate the schemas in the `/docs` folder.
- Pre-generated schemas are available in the repository under `/docs`.