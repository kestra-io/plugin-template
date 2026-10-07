Manage Mailchimp audiences, members, tags and campaigns from Kestra flows without custom scripts.

This plugin is a work in progress: tasks and triggers are being added.

## Authentication

Every task will accept either an `apiKey` or an OAuth `accessToken`, plus an optional `server` (data center prefix such as `us19`). Store credentials as secrets, e.g. `{{ secret('MAILCHIMP_API_KEY') }}`.
