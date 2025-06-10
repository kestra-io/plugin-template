# Kestra Plugin Devcontainer

This devcontainer provides a quick and easy setup for anyone using VSCode to get up and running quickly with plugin development for Kestra. It bootstraps a docker container for you to develop inside of without the need to manually setup the environment for developing plugins.

---

## INSTRUCTIONS

### Setup:

You can check out the original guide to starting plugin development here: https://kestra.io/docs/plugin-developer-guide

Once you have this repo cloned to your local system, you will need to install the VSCode extension [Remote Development](https://marketplace.visualstudio.com/items?itemName=ms-vscode-remote.vscode-remote-extensionpack).

Then run the following command from the command palette:
`Dev Containers: Open Folder in Container...` and select your Kestra root folder.

This will then put you inside a docker container ready for development.

NOTE: you'll need to wait for the gradle build to finish and compile Java files but this process should happen automatically within VSCode.

---

### Development:

You can then use the VSCode `Run and Debug` extension to start the Kestra server.

#### Plugins

If you want your plugins to be loaded inside your devcontainer, point the `source` field to a folder containing jars of the plugins you want to embed in the following snippet in `devcontainer.json`:

```
"mounts": [
  {
    "source": "/absolute/path/to/your/local/jar/plugins/folder",
    "target": "/workspaces/kestra/local/plugins",
    "type": "bind"
  }
],
```

---

### GIT

If you want to commit to GitHub, make sure to navigate to the `~/.ssh` folder and either create a new SSH key or override the existing `id_ed25519` file and paste an existing SSH key from your local machine into this file. You will then need to change the permissions of the file by running: `chmod 600 id_ed25519`. This will allow you to then push to GitHub.

---
