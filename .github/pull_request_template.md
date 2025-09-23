<!-- Thanks for submitting a Pull Request to kestra. To help us review your contribution, please follow the guidelines below:

- Make sure that your commits follow the [conventional commits](https://www.conventionalcommits.org/en/v1.0.0/) specification e.g. `feat(ui): add a new navigation menu item` or `fix(core): fix a bug in the core model` or `docs: update the README.md`. This will help us automatically generate the changelog.
- The title should briefly summarize the proposed changes.
- Provide a short overview of the change and the value it adds.
- Share a flow example to help the reviewer understand and QA the change.
- Use "close" to automatically close an issue. For example, `close #1234` will close issue #1234. -->

### What changes are being made and why?
<!-- Please include a brief summary of the changes included in this PR e.g. closes #1234. -->

---

### How the changes have been QAed?

<!-- Include example code that shows how this PR has been QAed. The code should present a complete yet easily reproducible flow.

```yaml
# Your example flow code here
```

Note that this is not a replacement for unit tests but rather a way to demonstrate how the changes work in a real-life scenario, as the end-user would experience them.

Remove this section if this change applies to all flows or to the documentation only. -->

---

### Setup Instructions

<!--If there are any setup requirements like API keys or trial accounts, kindly include brief bullet-points-description outlining the setup process below.

- [External System Documentation](URL)
- Steps to set up the necessary resources

If there are no setup requirements, you can remove this section.

Thank you for your contribution. ❤️  -->

### Contributor Checklist ✅

- [ ] PR Title and commits follows [conventional commits](https://www.conventionalcommits.org/en/v1.0.0/)
- [ ] Unit Tests added or updated to cover the change (using the `RunContext` to actually run tasks).
- [ ] Documentation updated (plugin docs from `@Schema` for properties and outputs, `@Plugin` with examples, `README.md` file with basic knowledge and specifics).
- [ ] Setup instructions included if needed (API keys, accounts, etc.).

⚙️ **Properties**
- [ ] Properties are declared with `Property<T>` carrier type, do **not** use `@PluginProperty`.

🌐 **HTTP**
- [ ] Must use Kestra’s internal HTTP client from `io.kestra.core.http.client`

📦 **JSON**
- [ ] Must use Jackson mappers provided by core (`io.kestra.core.serializers`)

✨ **New plugins/subplugins**
- [ ] Icons added in `src/main/resources/icons`
  - `plugin-icon.svg`
  - One icon per package, e.g. `io.kestra.plugin.apify.svg`
  - For subpackages, e.g. `io.kestra.plugin.aws.s3`, add `io.kestra.plugin.aws.s3.svg`
