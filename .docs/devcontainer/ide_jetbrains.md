JetBrains IDEs (IntelliJ IDEA)
===============================

Recommendations
- Use the project SDK set to Java 21.
- Configure Maven to use the wrapper: in Settings → Build Tools → Maven, set 'Maven home directory' to 'Wrapper' or point to the project mvnw.

Remote development
- For remote container development with JetBrains Gateway or Projector, ensure the container exposes the same JDK and Maven versions and mount the workspace.
