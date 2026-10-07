Manage Mailchimp audiences, members, tags and campaigns from Kestra flows without custom scripts.

This plugin is a work in progress: tasks and triggers are being added.

## What this plugin ships

- `Ping` checks that your credentials and data center are valid (`GET /ping`).

## Authentication

Every task and trigger accepts the same connection properties. Store credentials as secrets, e.g. `{{ secret('MAILCHIMP_API_KEY') }}`. Set exactly one of:

1. **API key** (`apiKey`): the data center is read from the key suffix (`...-us19`). Set `server` to override it.
2. **OAuth access token** (`accessToken`): also set `server` (for example `us19`). Run the OAuth code flow once outside Kestra and read the `dc` value from `https://login.mailchimp.com/oauth2/metadata`.

The credential is always sent in an `Authorization: Bearer` header, never in the URL. Redirects are not followed, so the credential can never reach another host. `baseUrl` replaces the API root for testing and proxies only; it must be `https`, or `http` on `localhost` / `127.0.0.1`.

## Example

```yaml
id: mailchimp_ping
namespace: company.team

tasks:
  - id: ping
    type: io.kestra.plugin.mailchimp.account.Ping
    apiKey: "{{ secret('MAILCHIMP_API_KEY') }}"
```

## Good to know

- HTTP 429 is retried with exponential backoff (1 s, doubling, capped at 30 s, with jitter) for every method. 5xx responses and I/O timeouts are retried only for GET and PUT. `maxRetries` defaults to 3; `0` disables retries.
- A `Retry-After` header is honored when Mailchimp sends one.
- Other 4xx answers fail immediately with the status, title, detail and per-field errors from Mailchimp. A 401 means the credentials are invalid or revoked.
- Tasks are sequential. Mailchimp limits each account to 10 simultaneous connections, so cap flow concurrency when many executions run at once.
- The per-call timeout defaults to 120 seconds, Mailchimp's own limit. Kestra's task-level `retry` stays available on top of the built-in one.
