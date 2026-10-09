Manage Mailchimp audiences, members, tags and campaigns, read campaign reports, and start flows on new subscribers or sent campaigns, without custom scripts.

## What this plugin ships

- `account.Ping` checks that your credentials and data center are valid.
- `audiences.ListAudiences` lists audiences (lists).
- `audiences.ListMembers` lists the members of an audience, with filters on status and change dates.
- `audiences.UpsertMember` adds a contact or updates an existing one (status, merge fields, tags, language, VIP).
- `audiences.BatchSubscribe` subscribes or updates many members from an ION file, in chunks of up to 500 per request.
- `audiences.UpdateMemberTags` adds and removes tags on one member.
- `campaigns.ListCampaigns` lists campaigns, with filters on type, status, audience and send time.
- `campaigns.SendCampaign` sends a campaign, after checking its send checklist.
- `reports.GetCampaignReport` returns the report summary (opens, clicks, bounces...) of a sent campaign.
- `reports.ListEmailActivity` lists what each recipient of a sent campaign did.
- `audiences.NewSubscriberTrigger` starts an execution when new members join an audience.
- `audiences.MemberStatusChangeTrigger` starts an execution when members get a given status, such as `unsubscribed`.
- `campaigns.CampaignSentTrigger` starts an execution when campaigns have been sent.

Tasks that return many items (`ListAudiences`, `ListMembers`, `ListCampaigns`, `ListEmailActivity`) take a `fetchType`: `FETCH` (default) returns the items in `rows`, `FETCH_ONE` returns the first one in `row`, `STORE` writes them as an ION file to Kestra internal storage and returns a `uri`, and `NONE` only counts them. Use `maxItems` to bound a large audience.

## Authentication

Every task and trigger accept the same connection properties. Store credentials as secrets, e.g. `{{ secret('MAILCHIMP_API_KEY') }}`. Set exactly one of:

1. **API key** (`apiKey`): the data center (`server`) is read from the key suffix, e.g. `-us19`. Set `server` only to override it.
2. **OAuth access token** (`accessToken`): `server` is then required (for example `us19`). Run the OAuth code flow once outside Kestra, read the `dc` value from `https://login.mailchimp.com/oauth2/metadata`, and store the token as a secret.

The credential is sent in an `Authorization: Bearer` header, never in the URL, and redirects are not followed. `baseUrl` replaces the API root for testing and proxies only. **It receives your credential**, so only set it to a host you trust; it must be `https`, or `http` on `localhost` / `127.0.0.1`.

## Example

```yaml
id: mailchimp_ping
namespace: company.team

tasks:
  - id: ping
    type: io.kestra.plugin.mailchimp.account.Ping
    apiKey: "{{ secret('MAILCHIMP_API_KEY') }}"
```

## Rate limits, retries and concurrency

- Mailchimp allows 10 simultaneous connections per user. Tasks send their requests one after the other, but many executions at once can exceed the limit, so cap the concurrency of flows that call Mailchimp.
- HTTP 429 is retried with exponential backoff (1 s, doubling, capped at 30 s, with jitter) for every method. 5xx responses and I/O timeouts are retried only for GET and PUT, so a POST (such as `SendCampaign` or `BatchSubscribe`) is not replayed after a 5xx or timeout; it is retried only when Mailchimp answers 429, which means the request was rejected. If one of them fails that way, check Mailchimp before re-running the flow.
- `maxRetries` defaults to 3; `0` disables retries. Kestra's task-level `retry` stays available on top.
- Other 4xx answers fail immediately with Mailchimp's status, title, detail and per-field errors. A 401 means the credentials are invalid or revoked.
- The per-call timeout defaults to 120 seconds, Mailchimp's own limit.

## Triggers

- The first poll fires nothing: it only records the current position, so existing members and campaigns do not start executions.
- Each poll reads one page of up to 1000 items. A larger backlog is drained over several polls.
- The position (cursor) is kept in the namespace KV Store, per flow and trigger.
- `interval` defaults to `PT5M` and cannot be lower than `PT30S`.
- Each execution gets a `uri` to an ION file holding the new items, oldest first.

## Consent and compliance

`UpsertMember` and `BatchSubscribe` can subscribe people directly. Only add contacts who gave consent, and use `statusIfNew: PENDING` for double opt-in. You are responsible for GDPR and Mailchimp's terms.

## Sending campaigns

`SendCampaign` is irreversible: Mailchimp emails the whole audience and the send cannot be recalled. By default the task fails without sending when the campaign's send checklist is not ready. Only send campaigns you have reviewed.

## Not included yet

OAuth token helper task, webhooks, the Mailchimp batch operations endpoint, and creating or editing campaigns and audiences.

Mailchimp API reference: https://mailchimp.com/developer/marketing/api/
