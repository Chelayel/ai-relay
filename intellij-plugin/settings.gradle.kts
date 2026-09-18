rootProject.name = "ai-relay-intellij"

// The plugin is a shell around the CLI: the CLI's jar (and its dependencies)
// are bundled into the plugin and run as a subprocess on the IDE's own Java.
// Building from the repo root keeps the two in step without publishing.
includeBuild("..")
