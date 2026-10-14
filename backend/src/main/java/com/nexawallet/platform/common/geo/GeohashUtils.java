package com.nexawallet.platform.common.geo;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Small, self-contained Base32 Geohash implementation: encode, decode (as a bounding box), and
 * 8-neighbor expansion. No external geohash dependency existed in this project, so this one was
 * added rather than pulling in a library for ~150 lines of well-known, easily-tested arithmetic.
 *
 * <h2>How this is actually used</h2>
 * {@code ServicePointSearchService} uses {@link #neighbors(String)} to build a <b>non-restrictive</b>
 * {@code should} clause in the Elasticsearch query — it only ever adds relevance weight to documents
 * near the search center's geohash cell, and can never exclude a document. The query's actual
 * correctness gate is Elasticsearch's own {@code geo_distance} filter, which computes true
 * great-circle distance natively and is correct at the poles and across the antimeridian without
 * any help from geohash arithmetic. This design was chosen deliberately: a geohash cell's
 * real-world width shrinks the closer the search center is to a pole (meridians converge), so
 * treating a fixed-precision geohash cell as a guaranteed "covers at least radius X" box is not
 * safe near the poles — using it only as a {@code should} hint sidesteps that failure mode
 * entirely while still genuinely using Geohash-based candidate selection, per the documented
 * architecture.
 */
public final class GeohashUtils {

    private static final String BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";
    private static final int MAX_PRECISION = 12;

    /** Approximate (equatorial, conservative) geohash cell dimensions in meters, by precision (1-indexed). */
    private static final double[] CELL_WIDTH_METERS = {
            5_009_400, 1_252_350, 156_500, 39_100, 4_890, 1_220, 152.9, 38.2, 4.77, 1.19, 0.149, 0.037
    };
    private static final double[] CELL_HEIGHT_METERS = {
            4_992_600, 624_100, 156_000, 19_500, 4_890, 610, 152.4, 19.0, 4.77, 0.595, 0.149, 0.0186
    };

    private GeohashUtils() {
    }

    /** Encodes a coordinate into a Base32 geohash string of the given precision (1-12). */
    public static String encode(double latitude, double longitude, int precision) {
        if (precision < 1 || precision > MAX_PRECISION) {
            throw new IllegalArgumentException("Geohash precision must be between 1 and " + MAX_PRECISION);
        }
        if (latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("Latitude out of range: " + latitude);
        }
        double normalizedLongitude = normalizeLongitude(longitude);

        double[] latRange = {-90.0, 90.0};
        double[] lonRange = {-180.0, 180.0};
        StringBuilder hash = new StringBuilder(precision);
        boolean isEvenBit = true;
        int bit = 0;
        int ch = 0;

        while (hash.length() < precision) {
            if (isEvenBit) {
                double mid = (lonRange[0] + lonRange[1]) / 2;
                if (normalizedLongitude >= mid) {
                    ch |= (1 << (4 - bit));
                    lonRange[0] = mid;
                } else {
                    lonRange[1] = mid;
                }
            } else {
                double mid = (latRange[0] + latRange[1]) / 2;
                if (latitude >= mid) {
                    ch |= (1 << (4 - bit));
                    latRange[0] = mid;
                } else {
                    latRange[1] = mid;
                }
            }
            isEvenBit = !isEvenBit;
            if (bit < 4) {
                bit++;
            } else {
                hash.append(BASE32.charAt(ch));
                bit = 0;
                ch = 0;
            }
        }
        return hash.toString();
    }

    /** Decodes a geohash into the bounding box it represents: {@code [latMin, latMax, lonMin, lonMax]}. */
    public static double[] decodeBoundingBox(String geohash) {
        double[] latRange = {-90.0, 90.0};
        double[] lonRange = {-180.0, 180.0};
        boolean isEvenBit = true;

        for (int i = 0; i < geohash.length(); i++) {
            int idx = BASE32.indexOf(geohash.charAt(i));
            if (idx < 0) {
                throw new IllegalArgumentException("Invalid geohash character: " + geohash.charAt(i));
            }
            for (int bit = 4; bit >= 0; bit--) {
                int bitValue = (idx >> bit) & 1;
                if (isEvenBit) {
                    double mid = (lonRange[0] + lonRange[1]) / 2;
                    if (bitValue == 1) {
                        lonRange[0] = mid;
                    } else {
                        lonRange[1] = mid;
                    }
                } else {
                    double mid = (latRange[0] + latRange[1]) / 2;
                    if (bitValue == 1) {
                        latRange[0] = mid;
                    } else {
                        latRange[1] = mid;
                    }
                }
                isEvenBit = !isEvenBit;
            }
        }
        return new double[]{latRange[0], latRange[1], lonRange[0], lonRange[1]};
    }

    /**
     * Returns the 8 geohashes surrounding {@code geohash} (N, S, E, W, NE, NW, SE, SW) at the same
     * precision, plus the cell itself — de-duplicated, so callers always get a {@link Set} of at
     * most 9 distinct cells (fewer very close to a pole, where several directions can collapse
     * onto the same cell). Longitude wraps correctly across the antimeridian; latitude is clamped
     * at the poles rather than wrapped, since there is no "other side" of a pole in this projection.
     */
    public static Set<String> neighbors(String geohash) {
        int precision = geohash.length();
        double[] box = decodeBoundingBox(geohash);
        double latMin = box[0], latMax = box[1], lonMin = box[2], lonMax = box[3];
        double latStep = latMax - latMin;
        double lonStep = lonMax - lonMin;
        double centerLat = (latMin + latMax) / 2;
        double centerLon = (lonMin + lonMax) / 2;

        Set<String> result = new LinkedHashSet<>();
        result.add(geohash);
        for (int dLat = -1; dLat <= 1; dLat++) {
            for (int dLon = -1; dLon <= 1; dLon++) {
                if (dLat == 0 && dLon == 0) {
                    continue;
                }
                double lat = clampLatitude(centerLat + dLat * latStep);
                double lon = normalizeLongitude(centerLon + dLon * lonStep);
                result.add(encode(lat, lon, precision));
            }
        }
        return result;
    }

    /**
     * Chooses a geohash precision whose cell dimensions are safely larger than {@code radiusMeters}
     * (equatorial, conservative estimate — see class javadoc for why this is only ever used as a
     * non-restrictive relevance hint, never as the sole correctness gate).
     */
    public static int precisionForRadiusMeters(double radiusMeters) {
        for (int precision = MAX_PRECISION; precision >= 1; precision--) {
            double width = CELL_WIDTH_METERS[precision - 1];
            double height = CELL_HEIGHT_METERS[precision - 1];
            if (Math.min(width, height) >= radiusMeters) {
                return precision;
            }
        }
        return 1;
    }

    private static double clampLatitude(double latitude) {
        return Math.max(-90.0, Math.min(90.0, latitude));
    }

    private static double normalizeLongitude(double longitude) {
        double normalized = longitude % 360.0;
        if (normalized < -180.0) {
            normalized += 360.0;
        } else if (normalized >= 180.0) {
            normalized -= 360.0;
        }
        return normalized;
    }
}
