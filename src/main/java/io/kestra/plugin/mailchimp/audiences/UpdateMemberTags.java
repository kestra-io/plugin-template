package io.kestra.plugin.mailchimp.audiences;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.mailchimp.AbstractMailchimpTask;
import io.kestra.plugin.mailchimp.MailchimpClient;
import io.kestra.plugin.mailchimp.models.SubscriberHash;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
@Schema(
    title = "Add or remove tags on a Mailchimp member",
    description = """
        Changes the tags of one member with `POST /lists/{listId}/members/{subscriberHash}/tags`. Each tag is set `active` (added) or `inactive` (removed). Mailchimp answers 204 without a body, so `applied` echoes what was sent.

        Only add contacts who have opted in; you are responsible for GDPR and Mailchimp terms."""
)
@Plugin(
    examples = {
        @Example(
            title = "Tag a contact and remove another tag.",
            full = true,
            code = """
                id: mailchimp_update_member_tags
                namespace: company.team

                tasks:
                  - id: tags
                    type: io.kestra.plugin.mailchimp.audiences.UpdateMemberTags
                    apiKey: "{{ secret('MAILCHIMP_API_KEY') }}"
                    listId: "a1b2c3d4e5"
                    email: "jane.doe@example.com"
                    tags:
                      - name: customer
                        status: active
                      - name: lead
                        status: inactive
                """
        )
    }
)
public class UpdateMemberTags extends AbstractMailchimpTask implements RunnableTask<UpdateMemberTags.Output> {
    private static final Set<String> STATUSES = Set.of("active", "inactive");

    @Schema(title = "Audience ID", description = "Mailchimp audience (list) id, found in the audience settings or with `ListAudiences`.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> listId;

    @Schema(title = "Email address", description = "Member email. Trimmed and lower-cased before it is hashed.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> email;

    @Schema(title = "Tags", description = "Tag changes, at least one. Each has a `name` and a `status` of `active` or `inactive`.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<List<Tag>> tags;

    @Schema(title = "Sync tags", description = "When true, the member's tags become exactly the `active` ones (`is_syncing`). Not sent when unset.")
    @PluginProperty(group = "advanced")
    private Property<Boolean> isSyncing;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rListId = runContext.render(listId).as(String.class).orElseThrow(() -> new IllegalArgumentException("'listId' is required"));
        var hash = SubscriberHash.of(runContext.render(email).as(String.class).orElseThrow(() -> new IllegalArgumentException("'email' is required")));
        var rTags = runContext.render(tags).asList(Tag.class);
        if (rTags.isEmpty()) {
            throw new IllegalArgumentException("'tags' must contain at least one tag");
        }
        var applied = rTags.stream().map(t -> {
            if (t.getName() == null || t.getName().isBlank()) {
                throw new IllegalArgumentException("Tag 'name' must not be blank");
            }
            var s = t.getStatus() == null ? "" : t.getStatus().trim().toLowerCase(Locale.ROOT);
            if (!STATUSES.contains(s)) {
                throw new IllegalArgumentException("Tag '" + t.getName() + "' has invalid status '" + t.getStatus() + "': expected active or inactive");
            }
            return new Tag(t.getName(), s);
        }).toList();

        var body = new LinkedHashMap<String, Object>();
        body.put("tags", applied);
        runContext.render(isSyncing).as(Boolean.class).ifPresent(v -> body.put("is_syncing", v));

        try (var client = client(runContext)) {
            client.send("POST", "/lists/" + MailchimpClient.segment(rListId) + "/members/" + MailchimpClient.segment(hash) + "/tags", null, body);
        }
        return Output.builder().applied(applied).build();
    }

    @Builder
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Tag {
        @Schema(title = "Tag name", description = "Name of the tag; it is created when it does not exist yet.")
        private String name;

        @Schema(title = "Tag status", description = "`active` adds the tag, `inactive` removes it.")
        private String status;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Applied tags", description = "Tag changes sent to Mailchimp, with normalized status.")
        private final List<Tag> applied;
    }
}
