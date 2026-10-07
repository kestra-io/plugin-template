package io.kestra.plugin.mailchimp.audiences;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
    title = "Add or update a member of a Mailchimp audience",
    description = """
        Creates the member if it does not exist, otherwise updates it, with `PUT /lists/{listId}/members/{subscriberHash}`. The hash is the MD5 of the trimmed, lower-cased email, so the same address always targets the same member. Calling it again with the same input is safe.

        `statusIfNew` is required and only applies when the member is created; `status` also changes an existing member. Optional fields you leave unset are not sent, so existing values are kept.

        Only add contacts who have opted in; you are responsible for GDPR and Mailchimp terms."""
)
@Plugin(
    examples = {
        @Example(
            title = "Subscribe a contact to an audience, or update them if they already exist.",
            full = true,
            code = """
                id: mailchimp_upsert_member
                namespace: company.team

                tasks:
                  - id: upsert
                    type: io.kestra.plugin.mailchimp.audiences.UpsertMember
                    apiKey: "{{ secret('MAILCHIMP_API_KEY') }}"
                    listId: "a1b2c3d4e5"
                    email: "jane.doe@example.com"
                    statusIfNew: SUBSCRIBED
                    mergeFields:
                      FNAME: Jane
                      LNAME: Doe
                    tags:
                      - newsletter
                """
        )
    }
)
public class UpsertMember extends AbstractMailchimpTask implements RunnableTask<UpsertMember.Output> {
    public enum Status { SUBSCRIBED, UNSUBSCRIBED, CLEANED, PENDING, TRANSACTIONAL }

    @Schema(title = "Audience ID", description = "Mailchimp audience (list) id, found in the audience settings or with `ListAudiences`.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> listId;

    @Schema(title = "Email address", description = "Contact email. Trimmed and lower-cased before it is hashed and sent.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> email;

    @Schema(title = "Status if new", description = "Status given when the member does not exist yet (`pending` sends a double opt-in email).")
    @NotNull
    @PluginProperty(group = "main")
    private Property<Status> statusIfNew;

    @Schema(title = "Status", description = "New status for the member, also applied to an existing member. Not sent when unset.")
    @PluginProperty(group = "processing")
    private Property<Status> status;

    @Schema(title = "Merge fields", description = "Merge field values by tag, e.g. `FNAME: Jane`.")
    @PluginProperty(group = "processing")
    private Property<Map<String, Object>> mergeFields;

    @Schema(title = "Tags", description = "Tag names to apply to the member.")
    @PluginProperty(group = "processing")
    private Property<List<String>> tags;

    @Schema(title = "Language", description = "Subscriber language code such as `en` or `fr`.")
    @PluginProperty(group = "processing")
    private Property<String> language;

    @Schema(title = "VIP", description = "Mark the member as VIP.")
    @PluginProperty(group = "processing")
    private Property<Boolean> vip;

    @Schema(title = "Skip merge validation", description = "Accept the member even when required merge fields are missing (`skip_merge_validation`).")
    @PluginProperty(group = "advanced")
    private Property<Boolean> skipMergeValidation;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rListId = runContext.render(listId).as(String.class).orElseThrow(() -> new IllegalArgumentException("'listId' is required"));
        var rEmail = runContext.render(email).as(String.class).orElseThrow(() -> new IllegalArgumentException("'email' is required")).trim().toLowerCase(Locale.ROOT);
        var hash = SubscriberHash.of(rEmail);
        var rStatusIfNew = runContext.render(statusIfNew).as(Status.class).orElseThrow(() -> new IllegalArgumentException("'statusIfNew' is required"));

        var body = new LinkedHashMap<String, Object>();
        body.put("email_address", rEmail);
        body.put("status_if_new", rStatusIfNew.name().toLowerCase(Locale.ROOT));
        runContext.render(status).as(Status.class).ifPresent(s -> body.put("status", s.name().toLowerCase(Locale.ROOT)));
        var rMerge = runContext.render(mergeFields).asMap(String.class, Object.class);
        if (!rMerge.isEmpty()) {
            body.put("merge_fields", rMerge);
        }
        var rTags = runContext.render(tags).asList(String.class);
        if (!rTags.isEmpty()) {
            body.put("tags", rTags);
        }
        runContext.render(language).as(String.class).filter(s -> !s.isBlank()).ifPresent(s -> body.put("language", s));
        runContext.render(vip).as(Boolean.class).ifPresent(v -> body.put("vip", v));

        var query = new LinkedHashMap<String, String>();
        runContext.render(skipMergeValidation).as(Boolean.class).filter(Boolean::booleanValue).ifPresent(v -> query.put("skip_merge_validation", "true"));

        try (var client = client(runContext)) {
            var response = client.send("PUT", "/lists/" + MailchimpClient.segment(rListId) + "/members/" + MailchimpClient.segment(hash), query, body);
            return Output.builder()
                .id(response.path("id").asText(hash))
                .emailAddress(response.path("email_address").asText(rEmail))
                .subscriberHash(hash)
                .status(response.path("status").asText(null))
                .build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Member ID", description = "Mailchimp member id (the subscriber hash).")
        private final String id;

        @Schema(title = "Email address", description = "Email address as stored by Mailchimp.")
        private final String emailAddress;

        @Schema(title = "Subscriber hash", description = "MD5 of the lower-cased email, used as member id in the API.")
        private final String subscriberHash;

        @Schema(title = "Status", description = "Member status after the call, e.g. `subscribed` or `pending`.")
        private final String status;
    }
}
