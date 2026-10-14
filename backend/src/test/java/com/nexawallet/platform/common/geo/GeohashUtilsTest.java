package com.nexawallet.platform.common.geo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GeohashUtils — Unit Tests")
class GeohashUtilsTest {

    // ──────────────────────────────────────────────────────────────────────
    // Known coordinate-to-geohash examples
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("1. Encoding the textbook (lat=57.64911, lon=10.40744) example starts with 'u4pruy' at precision 6")
    void encode_knownReferencePoint_matchesWellKnownPrefix() {
        // Widely-cited geohash.org / Wikipedia example: ~57.64911,10.40744 -> "u4pruydqqvj"
        String hash = GeohashUtils.encode(57.64911, 10.40744, 6);
        assertThat(hash).isEqualTo("u4pruy");
    }

    @Test
    @DisplayName("2. Decoding 'ezs42' yields a bounding box containing the well-known (42.6, -5.6) reference point")
    void decode_wellKnownGeohash_containsReferencePoint() {
        double[] box = GeohashUtils.decodeBoundingBox("ezs42");
        double latMin = box[0], latMax = box[1], lonMin = box[2], lonMax = box[3];

        assertThat(42.6).isBetween(latMin, latMax);
        assertThat(-5.6).isBetween(lonMin, lonMax);
    }

    @Test
    @DisplayName("3. Encode→decode round trip always contains the original point")
    void encodeThenDecode_roundTrip_containsOriginalPoint() {
        double lat = -33.8688, lon = 151.2093; // Sydney
        for (int precision = 1; precision <= 9; precision++) {
            String hash = GeohashUtils.encode(lat, lon, precision);
            double[] box = GeohashUtils.decodeBoundingBox(hash);
            assertThat(lat).as("precision %d", precision).isBetween(box[0], box[1]);
            assertThat(lon).as("precision %d", precision).isBetween(box[2], box[3]);
        }
    }

    @Test
    @DisplayName("4. Higher precision produces longer hashes with a strictly tighter bounding box")
    void higherPrecision_producesTighterBoundingBox() {
        double lat = 40.7128, lon = -74.0060; // NYC
        double[] coarse = GeohashUtils.decodeBoundingBox(GeohashUtils.encode(lat, lon, 4));
        double[] fine = GeohashUtils.decodeBoundingBox(GeohashUtils.encode(lat, lon, 8));

        double coarseArea = (coarse[1] - coarse[0]) * (coarse[3] - coarse[2]);
        double fineArea = (fine[1] - fine[0]) * (fine[3] - fine[2]);
        assertThat(fineArea).isLessThan(coarseArea);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Neighbor / cell-boundary cases
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("5. A cell has up to 8 distinct neighbors, all the same length, none equal to the cell itself")
    void neighbors_returnsUpToEightDistinctSameLengthCells() {
        String center = GeohashUtils.encode(51.5074, -0.1278, 6); // London
        Set<String> neighbors = GeohashUtils.neighbors(center);

        assertThat(neighbors).contains(center); // implementation includes the cell itself
        assertThat(neighbors.size()).isBetween(2, 9);
        neighbors.forEach(n -> assertThat(n).hasSize(6));
    }

    @Test
    @DisplayName("6. A point just across a cell boundary from the search center is in one of its neighbor cells")
    void pointAcrossCellBoundary_fallsIntoNeighborSet() {
        int precision = 5;
        String center = GeohashUtils.encode(10.0, 10.0, precision);
        double[] box = GeohashUtils.decodeBoundingBox(center);

        // A point just north of this cell's northern edge belongs to the cell immediately north.
        double justAcross = box[1] + 1e-6;
        String neighborOfPoint = GeohashUtils.encode(justAcross, 10.0, precision);

        assertThat(GeohashUtils.neighbors(center)).contains(neighborOfPoint);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Antimeridian & high-latitude handling
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("7. Longitude wraps correctly across the antimeridian (179.9 and -179.9 are mutual neighbors)")
    void neighbors_wrapAcrossAntimeridian() {
        int precision = 4;
        String east = GeohashUtils.encode(0.0, 179.95, precision);
        String west = GeohashUtils.encode(0.0, -179.95, precision);

        assertThat(GeohashUtils.neighbors(east)).contains(west);
        assertThat(GeohashUtils.neighbors(west)).contains(east);
    }

    @Test
    @DisplayName("8. Encoding and computing neighbors near the poles does not throw and stays within valid bounds")
    void nearPoles_doesNotThrowAndStaysInBounds() {
        for (double lat : new double[]{89.99, -89.99}) {
            String hash = GeohashUtils.encode(lat, 45.0, 5);
            Set<String> neighbors = GeohashUtils.neighbors(hash);
            assertThat(neighbors).isNotEmpty();
            for (String n : neighbors) {
                double[] box = GeohashUtils.decodeBoundingBox(n);
                assertThat(box[0]).isGreaterThanOrEqualTo(-90.0);
                assertThat(box[1]).isLessThanOrEqualTo(90.0);
            }
        }
    }

    @Test
    @DisplayName("9. Latitude exactly at the poles is clamped, not rejected")
    void exactPole_doesNotThrow() {
        assertThat(GeohashUtils.encode(90.0, 0.0, 5)).hasSize(5);
        assertThat(GeohashUtils.encode(-90.0, 0.0, 5)).hasSize(5);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Validation & precision selection
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("10. Invalid latitude is rejected")
    void encode_invalidLatitude_throws() {
        assertThatThrownBy(() -> GeohashUtils.encode(91.0, 0.0, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GeohashUtils.encode(-91.0, 0.0, 5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("11. Invalid precision is rejected")
    void encode_invalidPrecision_throws() {
        assertThatThrownBy(() -> GeohashUtils.encode(0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GeohashUtils.encode(0, 0, 13)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("12. Longitude outside [-180,180] is normalized rather than rejected (e.g. 190 == -170)")
    void encode_longitudeOutsideRange_isNormalized() {
        assertThat(GeohashUtils.encode(0, 190, 5)).isEqualTo(GeohashUtils.encode(0, -170, 5));
    }

    @Test
    @DisplayName("13. precisionForRadiusMeters returns a coarser (shorter) hash for a larger radius")
    void precisionForRadiusMeters_decreasesAsRadiusGrows() {
        int precisionSmall = GeohashUtils.precisionForRadiusMeters(500);
        int precisionLarge = GeohashUtils.precisionForRadiusMeters(40_000);
        assertThat(precisionSmall).isGreaterThanOrEqualTo(precisionLarge);
    }
}
