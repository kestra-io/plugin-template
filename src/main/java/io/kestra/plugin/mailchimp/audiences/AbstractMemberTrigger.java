package io.kestra.plugin.mailchimp.audiences;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.plugin.mailchimp.AbstractMailchimpTrigger;
import io.kestra.plugin.mailchimp.MailchimpClient;
import io.kestra.plugin.mailchimp.models.CursorPoll;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/** Audience-member polling shared by {@link NewSubscriberTrigger} and {@link MemberStatusChangeTrigger}. */
@SuperBuilder
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
public abstract class AbstractMemberTrigger extends AbstractMailchimpTrigger implements PollingTriggerInterface, TriggerOutput<AbstractMemberTrigger.Output> {
    @Schema(title = "Audience ID", description = "Mailchimp audience (list) id, found in the audience settings or with `ListAudiences`.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> listId;

    /**
     * @param query     fixed member filters (status)
     * @param timeField {@code timestamp_opt} or {@code last_changed}
     */
    protected Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context, String kind,
                                           Map<String, String> query, String timeField, Predicate<JsonNode> filter) throws Exception {
        checkInterval();
        var runContext = conditionContext.getRunContext();
        var rListId = runContext.render(listId).as(String.class).orElseThrow(() -> new IllegalArgumentException("'listId' is required"));
        var path = "/lists/" + MailchimpClient.segment(rListId) + "/members";

        try (var client = client(runContext)) {
            var result = CursorPoll.poll(runContext, context, client,
                new CursorPoll.Spec(kind, rListId, path, "members", query, timeField, filter, ListMembers::toRow));
            if (result.isEmpty()) {
                return Optional.empty();
            }
            var r = result.get();
            runContext.logger().info("{} new Mailchimp member event(s) in audience {}", r.count(), rListId);
            var execution = TriggerService.generateExecution(this, conditionContext, context, Output.builder()
                .listId(rListId)
                .count(r.count())
                .uri(r.uri())
                .cursor(r.cursor().format())
                .build());
            // Cursor saved only after the execution is built (at most once): if Kestra failed to emit it now,
            // these rows would not fire again (at most once), but a successful poll never fires them twice.
            r.commit();
            return Optional.of(execution);
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Audience ID")
        private final String listId;

        @Schema(title = "Number of new members", description = "Rows in the `uri` file.")
        private final Long count;

        @Schema(title = "New members URI", description = "ION file in internal storage, one row per member, same fields as `ListMembers`, oldest first.")
        private final URI uri;

        @Schema(title = "Cursor", description = "The trigger position after this poll (newest timestamp and the member ids seen at it), as stored in the KV store.")
        private final String cursor;
    }
}
