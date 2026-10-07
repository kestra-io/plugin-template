package io.kestra.plugin.mailchimp.models;

import java.net.URI;
import java.util.List;
import java.util.Map;

import io.kestra.core.models.tasks.Output;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/** Output shared by every task that returns a list of Mailchimp records, shaped by `fetchType`. */
@Builder
@Getter
public class FetchOutput implements Output {
    @Schema(title = "First record", description = "Populated when `fetchType` is `FETCH_ONE`.")
    private final Map<String, Object> row;

    @Schema(title = "Records", description = "Populated when `fetchType` is `FETCH`.")
    private final List<Map<String, Object>> rows;

    @Schema(title = "Stored records URI", description = "Populated when `fetchType` is `STORE`: an ION file in internal storage.")
    private final URI uri;

    @Schema(title = "Record count", description = "Number of records read from Mailchimp.")
    private final Long size;

    @Schema(title = "Total count", description = "Total number of matching records reported by Mailchimp (`total_items`), when available.")
    private final Long total;
}
