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

# Kestra Mailchimp Plugin

## Why

- Manage Mailchimp audiences, members, tags and campaigns from Kestra flows without custom API scripts, next to the rest of your data preparation, retries and notifications.

## What

Plugin components under `io.kestra.plugin.mailchimp`:

| Task or trigger | What it does |
|---|---|
| `account.Ping` | Check credentials and data center |
| `audiences.ListAudiences` | List audiences |
| `audiences.ListMembers` | List the members of an audience |
| `audiences.UpsertMember` | Add or update one member |
| `audiences.BatchSubscribe` | Subscribe or update many members from an ION file |
| `audiences.UpdateMemberTags` | Add or remove tags on a member |
| `campaigns.ListCampaigns` | List campaigns |
| `campaigns.SendCampaign` | Send a campaign (irreversible; checks the send checklist first) |
| `reports.GetCampaignReport` | Get the report summary of a sent campaign |
| `reports.ListEmailActivity` | List per-recipient activity of a sent campaign |
| `audiences.NewSubscriberTrigger` | Start an execution for new subscribers |
| `audiences.MemberStatusChangeTrigger` | Start an execution when members get a status (e.g. unsubscribed) |
| `campaigns.CampaignSentTrigger` | Start an execution for sent campaigns |

List tasks take a `fetchType` (`FETCH`, `FETCH_ONE`, `STORE`, `NONE`). Triggers poll every `PT5M` by default (minimum `PT30S`), keep their position in the namespace KV Store, and do not fire on the first poll.

## Authentication

Set exactly one of `apiKey` or `accessToken` on every task and trigger, and keep it in a secret:

- `apiKey`: the data center is read from the key suffix (`...-us19`). `server` overrides it.
- `accessToken` (OAuth): `server` is required. Get the token once outside Kestra and read `dc` from `https://login.mailchimp.com/oauth2/metadata`.

## Example

```yaml
id: mailchimp_ping
namespace: company.team

tasks:
  - id: ping
    type: io.kestra.plugin.mailchimp.account.Ping
    apiKey: "{{ secret('MAILCHIMP_API_KEY') }}"
```

See `src/main/resources/doc/io.kestra.plugin.mailchimp.md` for rate limits, retries, trigger behavior and consent notes.

## Setup

- JDK 21 to 23 (Lombok does not support newer JDKs yet) and Docker with Docker Compose.
- Credentials: copy `.env.example` to `.env` (git-ignored). The API key comes from Account > Extras > API keys in Mailchimp; the data center is the part after the dash in the key. Use a sandbox account.
- `./gradlew test` runs the unit tests (no Mailchimp account needed). `./gradlew build` also lints the plugin docs.

## Running Kestra locally with this plugin

1. Build the plugin: `./gradlew build` (or `./gradlew shadowJar`). The jar lands in `build/libs/`.
2. Run `docker compose up`. `docker-compose.yml` builds the Kestra image from `Dockerfile` and mounts `build/libs/` to `/app/plugins/`, so Kestra picks up the jar on startup.
3. Kestra UI is available at [localhost:8080](http://localhost:8080).
4. `{{ secret('MAILCHIMP_API_KEY') }}` reads the environment variable `SECRET_MAILCHIMP_API_KEY`, whose value is the base64 of the key (see `.env.example`).

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
