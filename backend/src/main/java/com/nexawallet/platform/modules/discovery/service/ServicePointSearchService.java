package com.nexawallet.platform.modules.discovery.service;

import co.elastic.clients.elasticsearch._types.GeoLocation;
import co.elastic.clients.elasticsearch._types.LatLonGeoLocation;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.nexawallet.platform.common.geo.GeohashUtils;
import com.nexawallet.platform.modules.discovery.config.DiscoveryProperties;
import com.nexawallet.platform.modules.discovery.document.ServicePointDocument;
import com.nexawallet.platform.modules.discovery.dto.NearbyServicePointResponse;
import com.nexawallet.platform.modules.discovery.dto.NearbySearchResponse;
import com.nexawallet.platform.modules.discovery.entity.ServicePointType;
import com.nexawallet.platform.modules.discovery.exception.SearchUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Nearby-service-point search: a two-part Elasticsearch query where each part has a clear job.
 *
 * <ol>
 *   <li><b>Correctness</b> — a {@code geo_distance} filter clause (always present, {@code must}):
 *       Elasticsearch computes true great-circle distance natively, correctly at the poles and
 *       across the antimeridian, with no help from geohash arithmetic. This is the only thing
 *       that decides whether a document is "within radius."</li>
 *   <li><b>Candidate-selection hint</b> — a {@code should} clause over the search center's
 *       geohash cell and its 8 neighbors ({@link GeohashUtils#neighbors(String)}), using the
 *       stored {@code geohash} keyword field. Being a {@code should}, it can only add relevance
 *       weight — it can never exclude a document the {@code geo_distance} filter already accepted.
 *       See {@link GeohashUtils} javadoc for why it is deliberately never used as a restrictive
 *       filter (a fixed geohash cell's real-world width shrinks near the poles, which would make a
 *       restrictive version unsafe exactly at the latitudes the project is required to handle).</li>
 * </ol>
 *
 * Sorting is primarily by {@code _geo_distance} (ascending, meters) with {@code _id} as a
 * deterministic secondary sort for exact ties. Because this is one Elasticsearch query — not
 * several per-cell queries merged client-side — a document can never appear twice in the results,
 * regardless of how many neighboring cells it matches.
 */
@Service
public class ServicePointSearchService {

    private static final Logger log = LoggerFactory.getLogger(ServicePointSearchService.class);
    private static final int CANDIDATE_GEOHASH_PRECISION_CAP = 6; // ~1.2km cells at most — keeps the should-clause small

    private final ElasticsearchOperations elasticsearchOperations;
    private final DiscoveryProperties properties;

    public ServicePointSearchService(ElasticsearchOperations elasticsearchOperations, DiscoveryProperties properties) {
        this.elasticsearchOperations = elasticsearchOperations;
        this.properties = properties;
    }

    public NearbySearchResponse search(Double latitude, Double longitude, Double radiusMeters, String category, Integer page, Integer size) {
        validateCoordinates(latitude, longitude);
        double effectiveRadius = validateRadius(radiusMeters);
        int effectivePage = validatePage(page);
        int effectiveSize = validateSize(size);
        String normalizedCategory = validateCategory(category);

        Query geoDistanceFilter = Query.of(q -> q.geoDistance(gd -> gd
                .field("location")
                .distance(((long) effectiveRadius) + "m")
                .location(GeoLocation.of(l -> l.latlon(LatLonGeoLocation.of(ll -> ll.lat(latitude).lon(longitude)))))));

        Query activeFilter = Query.of(q -> q.term(t -> t.field("active").value(true)));

        List<Query> filters = new ArrayList<>();
        filters.add(geoDistanceFilter);
        filters.add(activeFilter);
        if (normalizedCategory != null) {
            filters.add(Query.of(q -> q.term(t -> t.field("type").value(normalizedCategory))));
        }

        int candidatePrecision = Math.min(GeohashUtils.precisionForRadiusMeters(effectiveRadius), CANDIDATE_GEOHASH_PRECISION_CAP);
        Set<String> candidateCells = GeohashUtils.neighbors(GeohashUtils.encode(latitude, longitude, candidatePrecision));
        List<Query> shoulds = candidateCells.stream()
                .map(cell -> Query.of(q -> q.prefix(p -> p.field("geohash").value(cell))))
                .toList();

        Query finalQuery = Query.of(q -> q.bool(b -> {
            b.filter(filters);
            b.should(shoulds);
            return b;
        }));

        SortOptions distanceSort = SortOptions.of(s -> s.geoDistance(gd -> gd
                .field("location")
                .location(List.of(GeoLocation.of(l -> l.latlon(LatLonGeoLocation.of(ll -> ll.lat(latitude).lon(longitude))))))
                .order(SortOrder.Asc)
                .unit(co.elastic.clients.elasticsearch._types.DistanceUnit.Meters)));
        SortOptions tieBreakSort = SortOptions.of(s -> s.field(f -> f.field("_id").order(SortOrder.Asc)));

        NativeQuery nativeQuery = NativeQuery.builder()
                .withQuery(finalQuery)
                .withSort(distanceSort, tieBreakSort)
                .withPageable(PageRequest.of(effectivePage, effectiveSize))
                .build();

        try {
            SearchHits<ServicePointDocument> hits = elasticsearchOperations.search(nativeQuery, ServicePointDocument.class);
            List<NearbyServicePointResponse> content = hits.getSearchHits().stream()
                    .map(this::toResponse)
                    .toList();
            return NearbySearchResponse.builder()
                    .content(content)
                    .page(effectivePage)
                    .size(effectiveSize)
                    .totalElements(hits.getTotalHits())
                    .build();
        } catch (SearchUnavailableException propagate) {
            throw propagate;
        } catch (Exception elasticsearchUnavailable) {
            log.error("Nearby service-point search failed: {}", elasticsearchUnavailable.getClass().getSimpleName());
            throw new SearchUnavailableException("Elasticsearch search failed", elasticsearchUnavailable);
        }
    }

    private NearbyServicePointResponse toResponse(SearchHit<ServicePointDocument> hit) {
        ServicePointDocument doc = hit.getContent();
        double distanceMeters = 0;
        List<Object> sortValues = hit.getSortValues();
        if (!sortValues.isEmpty() && sortValues.get(0) instanceof Number number) {
            distanceMeters = number.doubleValue();
        }
        return NearbyServicePointResponse.builder()
                .id(doc.getId())
                .name(doc.getName())
                .type(doc.getType())
                .latitude(doc.getLocation() != null ? doc.getLocation().getLat() : 0)
                .longitude(doc.getLocation() != null ? doc.getLocation().getLon() : 0)
                .address(doc.getAddress())
                .distanceMeters(distanceMeters)
                .build();
    }

    private void validateCoordinates(Double latitude, Double longitude) {
        if (latitude == null || longitude == null) {
            throw new IllegalArgumentException("Both lat and lon are required");
        }
        if (latitude < -90.0 || latitude > 90.0) {
            throw new IllegalArgumentException("lat must be between -90 and 90");
        }
        if (longitude < -180.0 || longitude > 180.0) {
            throw new IllegalArgumentException("lon must be between -180 and 180");
        }
    }

    private double validateRadius(Double radiusMeters) {
        double effective = radiusMeters != null ? radiusMeters : properties.getDefaultRadiusMeters();
        if (effective <= 0) {
            throw new IllegalArgumentException("radiusMeters must be greater than zero");
        }
        if (effective > properties.getMaxRadiusMeters()) {
            throw new IllegalArgumentException("radiusMeters must not exceed " + (long) properties.getMaxRadiusMeters());
        }
        return effective;
    }

    private int validatePage(Integer page) {
        int effective = page != null ? page : 0;
        if (effective < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        return effective;
    }

    private int validateSize(Integer size) {
        int effective = size != null ? size : properties.getDefaultPageSize();
        if (effective <= 0) {
            throw new IllegalArgumentException("size must be greater than zero");
        }
        if (effective > properties.getMaxPageSize()) {
            throw new IllegalArgumentException("size must not exceed " + properties.getMaxPageSize());
        }
        return effective;
    }

    private String validateCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        try {
            return ServicePointType.valueOf(category.trim().toUpperCase()).name();
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("category must be one of: ATM, BRANCH, PARTNER_KIOSK, MERCHANT");
        }
    }
}
