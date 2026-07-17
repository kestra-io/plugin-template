<p align="center">
  <a href="https://www.kestra.io">
    <img src="https://kestra.io/banner.png"  alt="Kestra workflow orchestrator" />
  </a>
</p>

<h1 align="center" style="border-bottom: none">
    Event-Driven Declarative Orchestrator
</h1>

<div align="center">
 <a href="https://github.com/kestra-io/kestra/releases"><img src="https://img.shields.io/github/tag-pre/kestra-io/kestra.svg?color=blueviolet" alt="Last Version" /></a>
  <a href="https://github.com/kestra-io/kestra/blob/develop/LICENSE"><img src="https://img.shields.io/github/license/kestra-io/kestra?color=blueviolet" alt="License" /></a>
  <a href="https://github.com/kestra-io/kestra/stargazers"><img src="https://img.shields.io/github/stars/kestra-io/kestra?color=blueviolet&logo=github" alt="Github star" /></a> <br>
<a href="https://kestra.io"><img src="https://img.shields.io/badge/Website-kestra.io-192A4E?color=blueviolet" alt="Kestra infinitely scalable orchestration and scheduling platform"></a>
<a href="https://kestra.io/slack"><img src="https://img.shields.io/badge/Slack-Join%20Community-blueviolet?logo=slack" alt="Slack"></a>
</div>

<br />

<p align="center">
  <a href="https://twitter.com/kestra_io" style="margin: 0 10px;">
        <img src="https://kestra.io/twitter.svg" alt="twitter" width="35" height="25" /></a>
  <a href="https://www.linkedin.com/company/kestra/" style="margin: 0 10px;">
        <img src="https://kestra.io/linkedin.svg" alt="linkedin" width="35" height="25" /></a>
  <a href="https://www.youtube.com/@kestra-io" style="margin: 0 10px;">
        <img src="https://kestra.io/youtube.svg" alt="youtube" width="35" height="25" /></a>
</p>

<br />
<p align="center">
    <a href="https://go.kestra.io/video/product-overview" target="_blank">
        <img src="https://kestra.io/startvideo.png" alt="Get started in 3 minutes with Kestra" width="640px" />
    </a>
</p>
<p align="center" style="color:grey;"><i>Get started with Kestra in 3 minutes.</i></p>

# Kestra Plugin Template

## Why

- What user problem does this solve? Teams need a concrete starting point for building and validating new Kestra plugins without recreating the same project scaffolding from scratch.
- Why would a team adopt this plugin in a workflow? It gives plugin authors a ready-made reference repo they can adapt alongside their own build, test, and publishing workflow.
- What operational/business outcome does it enable? It shortens plugin delivery time, reduces setup mistakes, and makes internal or partner plugin development more repeatable.

## What

- Provides plugin components under `io.kestra.plugin.templates`.
- Includes classes such as `Example`, `Trigger`.

## Running Kestra locally with this plugin

1. Build the shadow JAR: `./gradlew shadowJar`. The output lands in `build/libs/`.
2. Run `docker compose up`. `docker-compose.yml` builds `kestra/kestra:latest` and mounts `build/libs/` to `/app/plugins/`, so Kestra picks up the jar on startup.
3. Kestra UI is available at [localhost:8080](http://localhost:8080).

### Plugins folder gotcha

Mounting a host folder onto `/app/plugins/` replaces the container's plugins directory rather than adding to it. Core plugins (the ones logged as `Registered N core plugins`) are compiled into Kestra itself and aren't affected, but any additional plugin normally bundled in the base image under `/app/plugins/` (e.g. the Python script plugin) gets hidden once the mount is in place. If a flow you're testing depends on another plugin, copy its jar into `build/libs/` too before starting the container.

### JFR startup error

On some hosts, `command: server local` fails with:
```
Unable to create JFR repository directory using base location (/tmp)
```
`docker-compose.yml` works around this by mounting `/tmp` as `tmpfs`. If you build your own compose file or run Kestra via `docker run`, add the same workaround, e.g. `-v /tmp:/tmp` or `--tmpfs /tmp`. Tracked upstream in [kestra-io/kestra#17405](https://github.com/kestra-io/kestra/issues/17405).

## Documentation
* Full documentation can be found under: [kestra.io/docs](https://kestra.io/docs)
* Documentation for developing a plugin is included in the [Plugin Developer Guide](https://kestra.io/docs/plugin-developer-guide/)


## License
Apache 2.0 © [Kestra Technologies](https://kestra.io)


## Stay up to date

We release new versions every month. Give the [main repository](https://github.com/kestra-io/kestra) a star to stay up to date with the latest releases and get notified about future updates.

![Star the repo](https://kestra.io/star.gif)
