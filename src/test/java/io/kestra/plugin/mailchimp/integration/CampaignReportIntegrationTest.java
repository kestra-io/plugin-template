package io.kestra.plugin.mailchimp.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.kestra.core.models.property.Property;
import io.kestra.plugin.mailchimp.reports.GetCampaignReport;
import io.kestra.plugin.mailchimp.reports.ListEmailActivity;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/** Read-only report calls on an already sent campaign (optional {@code MAILCHIMP_CAMPAIGN_ID}). */
@EnabledIfEnvironmentVariable(named = "MAILCHIMP_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "MAILCHIMP_CAMPAIGN_ID", matches = ".+")
class CampaignReportIntegrationTest extends MailchimpIntegrationBase {
    @Test
    void getCampaignReport() throws Exception {
        var out = GetCampaignReport.builder().apiKey(apiKeyProperty()).server(serverProperty())
            .campaignId(Property.ofValue(campaignId())).build().run(runContextFactory.of());

        assertThat(out.getRow(), notNullValue());
    }

    @Test
    void listEmailActivity() throws Exception {
        var out = ListEmailActivity.builder().apiKey(apiKeyProperty()).server(serverProperty())
            .campaignId(Property.ofValue(campaignId())).maxItems(Property.ofValue(10)).build().run(runContextFactory.of());

        assertThat(out.getRows(), notNullValue());
        assertThat(out.getSize(), equalTo((long) out.getRows().size()));
    }
}
