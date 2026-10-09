package io.kestra.plugin.mailchimp.audiences;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.TriggerContext;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
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
    title = "Trigger on Mailchimp member status changes",
    description = """
        Polls `GET /lists/{listId}/members` for members with the given `status`, by last change time (`last_changed`), and starts one execution per poll with every member changed since the last poll, stored as an ION file (`trigger.uri`, `trigger.count`).

        `last_changed` also moves on edits that do not change the status (merge fields, tags...), so an already unsubscribed member that is edited fires again. Rows with another status are ignored.

        The position is kept in the namespace KV Store, per flow, trigger, status and audience (changing `status` starts over with a first poll): the newest `last_changed` seen plus the ids seen at exactly that time. The first poll only records the current position. Each poll reads at most 1000 rows (more only when they all share one timestamp), so a backlog larger than 1000 drains over several polls."""
)
@Plugin(
    examples = {
        @Example(
            title = "Push unsubscribes to a suppression list.",
            full = true,
            code = """
                id: mailchimp_unsubscribes
                namespace: company.team

                triggers:
                  - id: unsubscribed
                    type: io.kestra.plugin.mailchimp.audiences.MemberStatusChangeTrigger
                    apiKey: "{{ secret('MAILCHIMP_API_KEY') }}"
                    listId: "a1b2c3d4e5"
                    status: UNSUBSCRIBED
                    interval: PT10M

                tasks:
                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ trigger.count }} member(s) unsubscribed: {{ trigger.uri }}"
                """
        )
    }
)
public class MemberStatusChangeTrigger extends AbstractMemberTrigger {
    public enum Status { SUBSCRIBED, UNSUBSCRIBED, CLEANED, PENDING, TRANSACTIONAL }

    @Schema(title = "Status", description = "Fire for members that now have this status (sent lower case, e.g. `unsubscribed`).")
    @NotNull
    @PluginProperty(group = "main")
    private Property<Status> status;

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        var rStatus = conditionContext.getRunContext().render(status).as(Status.class)
            .orElseThrow(() -> new IllegalArgumentException("'status' is required"))
            .name().toLowerCase(Locale.ROOT);
        // filtered server-side, checked again in case a row with another status slips through
        return evaluate(conditionContext, context, "member_status_" + rStatus, Map.of("status", rStatus), "last_changed",
            node -> rStatus.equalsIgnoreCase(node.path("status").asText()));
    }
}
