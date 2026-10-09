package io.kestra.plugin.mailchimp.audiences;

import java.util.Map;
import java.util.Optional;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.triggers.TriggerContext;

import io.swagger.v3.oas.annotations.media.Schema;
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
    title = "Trigger on new Mailchimp subscribers",
    description = """
        Polls `GET /lists/{listId}/members` for subscribed members by opt-in time (`timestamp_opt`) and starts one execution per poll with every new subscriber, stored as an ION file (`trigger.uri`, `trigger.count`).

        The position is kept in the namespace KV Store, per flow, trigger and audience: the newest `timestamp_opt` seen plus the ids seen at exactly that time, so members opting in during the same second are neither lost nor fired twice. The first poll only records the current position: existing subscribers do not fire. Each poll reads at most 1000 rows (more only when they all share one timestamp), so a backlog larger than 1000 drains over several polls.

        Members are tracked by opt-in time (`timestamp_opt`): a member imported or added with a `timestamp_opt` older than the stored position (for example a backfill import keeping the original opt-in dates) does not fire."""
)
@Plugin(
    examples = {
        @Example(
            title = "Log every new subscriber of an audience.",
            full = true,
            code = """
                id: mailchimp_new_subscriber
                namespace: company.team

                triggers:
                  - id: new_subscriber
                    type: io.kestra.plugin.mailchimp.audiences.NewSubscriberTrigger
                    apiKey: "{{ secret('MAILCHIMP_API_KEY') }}"
                    listId: "a1b2c3d4e5"
                    interval: PT5M

                tasks:
                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "{{ trigger.count }} new subscriber(s) in {{ trigger.listId }}: {{ trigger.uri }}"
                """
        )
    }
)
public class NewSubscriberTrigger extends AbstractMemberTrigger {
    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        return evaluate(conditionContext, context, "new_subscriber", Map.of("status", "subscribed"), "timestamp_opt", node -> true);
    }
}
