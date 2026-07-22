Usage Examples
==============

Sample input configuration
--------------------------
Save a JSON file (example: `configs/train-ticket.json`) with the following structure and use it as the `configPath` parameter to the API calls below:

```json
{
  "systemName": "Train-ticket",
  "repositoryURL": "https://github.com/g-goulis/train-ticket-microservices-test.git",
  "endCommit": "06f3e1efe2e2539d05d91b0699cc8d9fe7be29d7",
  "baseBranch": "main"
}
```

Generate an IR (programmatic)
---------------------------
In Java code:

```java
Config conf = ConfigUtil.readConfigFromFile(Path.of("configs/train-ticket.json"));
MicroserviceSystem system = IRExtractionService.create(conf);
IRExtractionService.createAndWrite(conf, "output/IR.json");
```

Generate an IR (CLI / quick)
---------------------------
From the project root (use wrapper):

```
./mvnw -Dtest=JSONDocumentationTest test
```

Sample IR output (truncated)
---------------------------
```json
{
  "name": "Train-ticket",
  "commitID": "1.0",
  "microservices": [
    {
      "name": "ts-rebook-service",
      "path": "./clone/train-ticket-microservices-test/ts-rebook-service",
      "controllers": [
        {
          "packageName": "com.cloudhubs.trainticket.rebook.controller",
          "name": "WaitListOrderController.java",
          "path": ".../WaitListOrderController.java",
          "classRole": "CONTROLLER",
          "annotations": [{"name":"RequestMapping","contents":"\"/api/v1/waitorderservice\""}],
          "methods": [{"name":"getAllOrders","url":"/api/v1/waitorderservice/orders","httpMethod":"GET"}],
          "methodCalls": [{"name":"info","objectName":"LOGGER"}]
        }
      ],
      "Services": [...],
      "Repositories": [...],
      "Entities": [...]
    }
  ],
  "orphans": []
}
```

Sample SystemChange (delta) output (truncated)
---------------------------------------------
```json
{
  "oldCommit": "06f3e1efe2e2539d05d91b0699cc8d9fe7be29d7",
  "newCommit": "82949fa07dcf82f66641f5807d629d15bab663a6",
  "changes": [
    {
      "oldPath": ".../PriceController.java",
      "newPath": ".../PriceController.java",
      "changeType": "MODIFY",
      "classChange": {}
    }
  ]
}
```

Where outputs are written
------------------------
- IRs and deltas are written to `./output/` by default. Partial IRs are written as `PART_<repo>_<branch>_<commit>.json`.
- JSON Schemas are written to `./.docs/schema/` by default (use `JsonSchemaService.writeSchemas()`).
