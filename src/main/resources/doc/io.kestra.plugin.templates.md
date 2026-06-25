This is the Kestra plugin template. Use it as a starting point for building a new plugin.

## What this template ships

- `Example` is a sample `RunnableTask` that reverses an input string.
- `Trigger` is a sample polling trigger that fires an execution at random.

## How to build your plugin

1. Rename the package `io.kestra.plugin.templates` to your own, for example `io.kestra.plugin.myservice`.
2. Update `group`, `name`, `title`, and `description` in `src/main/resources/metadata/index.yaml`.
3. Replace `src/main/resources/icons/plugin-icon.svg` with your service's icon.
4. Replace the `Example` and `Trigger` classes with your real tasks and triggers.
5. Replace this how-to with documentation for your plugin.

Run `./gradlew lintPluginDocs` before pushing to validate the plugin documentation.
