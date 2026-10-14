package com.nexawallet.platform.modules.discovery.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.GeoPointField;
import org.springframework.data.elasticsearch.core.geo.GeoPoint;

import java.time.OffsetDateTime;

/**
 * Elasticsearch document for service-point discovery — a <b>derived</b> search index, never the
 * source of truth. PostgreSQL's {@code service_points} table remains authoritative; this document
 * must always be fully recreatable from it (see {@code ServicePointReindexService}).
 *
 * Deliberately minimal: only the fields a "find a nearby ATM/branch/kiosk" search needs. No wallet
 * balances, ledger data, transaction details, credentials, or tokens — this index has no financial
 * data in it at all, by construction.
 *
 * The index name is versioned ({@code service_points_v1} by default, overridable via
 * {@code nexa.elasticsearch.index.service-points}) so a future breaking mapping change can ship as
 * a new index name (e.g. {@code service_points_v2}) and be backfilled via
 * {@code ServicePointReindexService} without disrupting the one currently being searched.
 */
@Document(indexName = "${nexa.elasticsearch.index.service-points:service_points_v1}")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServicePointDocument {

    /** Same UUID as {@code service_points.id} in PostgreSQL — used as the Elasticsearch {@code _id} too, so every write is a natural upsert. */
    @Id
    private String id;

    @Field(type = FieldType.Keyword)
    private String name;

    @Field(type = FieldType.Keyword)
    private String type;

    @Field(type = FieldType.Boolean)
    private boolean active;

    @GeoPointField
    private GeoPoint location;

    /** Stored for reference/aggregation only — see {@code GeohashUtils} javadoc for why it is never the sole correctness gate in a search query. */
    @Field(type = FieldType.Keyword)
    private String geohash;

    @Field(type = FieldType.Text)
    private String address;

    @Field(type = FieldType.Date, format = DateFormat.date_time)
    private OffsetDateTime updatedAt;
}
