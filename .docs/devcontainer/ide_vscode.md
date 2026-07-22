VS Code Support
================

Recommended extensions
- See `.devcontainer/devcontainer.json` for the exact extension list that will be installed into the container for VS Code sessions.

Workspace settings
- Settings that affect Java import behavior are in the devcontainer customizations: `java.import.gradle.enabled = false` and `java.import.maven.enabled = true`.

Debugging and launching
- Use the Java Debugger included in the Java Pack.
- Remote debugging: devcontainer forwards port 5005 by default (see devcontainer.json). Attach VS Code debugger to that port when starting JVM with JDWP flags.
