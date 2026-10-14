import React, { useId, useState } from 'react';
import { Compass, MapPin, Loader2, AlertTriangle, CloudOff, SearchX, LocateFixed, ChevronLeft, ChevronRight, RefreshCw } from 'lucide-react';
import { fetchNearbyServicePoints, extractApiErrorMessage, type NearbyServicePoint } from '../../services/api';
import { formatDistance } from '../../utils/money';

type SearchState = 'idle' | 'loading' | 'success' | 'empty' | 'validation-error' | 'unavailable';

const CATEGORIES = ['', 'ATM', 'BRANCH', 'PARTNER_KIOSK', 'MERCHANT'];
// Backend default bounds (nexa.discovery.*): max 50km, default page size 20. Offered here as a
// fixed set of sensible choices rather than free-text, so a request can never exceed the limit.
const RADIUS_OPTIONS_METERS = [500, 1000, 2000, 5000, 10000, 25000, 50000];
const PAGE_SIZE = 10;

/**
 * Nearby-service-point search widget against the public Phase 6 discovery API
 * (`GET /api/v1/service-points/nearby`). Never requests browser geolocation automatically —
 * "Use my current location" only triggers the browser's permission prompt on an explicit click,
 * and manual lat/lon entry always works as a fallback (including when geolocation is denied,
 * unavailable, or the user simply prefers not to share it).
 */
export const NearbyServicePoints: React.FC = () => {
  const [lat, setLat] = useState('40.7128');
  const [lon, setLon] = useState('-74.0060');
  const [radiusMeters, setRadiusMeters] = useState(2000);
  const [category, setCategory] = useState('');
  const [page, setPage] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [state, setState] = useState<SearchState>('idle');
  const [errorMessage, setErrorMessage] = useState('');
  const [results, setResults] = useState<NearbyServicePoint[]>([]);
  const [locating, setLocating] = useState(false);
  const [locationNotice, setLocationNotice] = useState<string | null>(null);

  const latId = useId();
  const lonId = useId();
  const radiusId = useId();
  const categoryId = useId();

  const useMyLocation = () => {
    if (!navigator.geolocation) {
      setLocationNotice('Geolocation is not supported by this browser — enter coordinates manually instead.');
      return;
    }
    setLocationNotice(null);
    setLocating(true);
    navigator.geolocation.getCurrentPosition(
      (position) => {
        setLat(position.coords.latitude.toFixed(6));
        setLon(position.coords.longitude.toFixed(6));
        setLocating(false);
      },
      () => {
        setLocationNotice('Location permission was denied or is unavailable right now — enter coordinates manually instead.');
        setLocating(false);
      },
      { timeout: 10000 }
    );
  };

  const runSearch = async (targetPage: number) => {
    const latNum = Number(lat);
    const lonNum = Number(lon);

    if (Number.isNaN(latNum) || latNum < -90 || latNum > 90) {
      setErrorMessage('Latitude must be a number between -90 and 90.');
      setState('validation-error');
      return;
    }
    if (Number.isNaN(lonNum) || lonNum < -180 || lonNum > 180) {
      setErrorMessage('Longitude must be a number between -180 and 180.');
      setState('validation-error');
      return;
    }

    setState('loading');
    try {
      const result = await fetchNearbyServicePoints({
        lat: latNum,
        lon: lonNum,
        radiusMeters,
        category: category || undefined,
        page: targetPage,
        size: PAGE_SIZE,
      });
      setResults(result.content);
      setTotalElements(result.totalElements);
      setPage(result.page);
      setState(result.content.length === 0 ? 'empty' : 'success');
    } catch (err) {
      setErrorMessage(extractApiErrorMessage(err, 'Something went wrong while searching. Please try again.'));
      setState('unavailable');
    }
  };

  const search = (e: React.FormEvent) => {
    e.preventDefault();
    runSearch(0);
  };

  const totalPages = Math.max(1, Math.ceil(totalElements / PAGE_SIZE));

  return (
    <div id="discovery" className="p-6 rounded-2xl bg-nexa-navy-900 border border-nexa-navy-800 shadow-xl space-y-5">
      <div className="flex items-center justify-between">
        <div className="flex items-center space-x-2">
          <div className="p-2 rounded-xl bg-nexa-teal-600/20 text-nexa-teal-400">
            <Compass className="w-5 h-5" />
          </div>
          <div>
            <h2 className="text-base font-bold text-white">Nearby Service Points</h2>
            <p className="text-xs text-slate-400">Geohash-assisted, Elasticsearch geo-distance search</p>
          </div>
        </div>
      </div>

      <form onSubmit={search} className="grid grid-cols-2 sm:grid-cols-5 gap-3 items-end">
        <div className="col-span-1">
          <label htmlFor={latId} className="text-[11px] text-slate-400 block mb-1">Latitude</label>
          <input
            id={latId}
            value={lat}
            onChange={(e) => setLat(e.target.value)}
            className="w-full px-3 py-2 rounded-lg bg-nexa-navy-950 border border-nexa-navy-700 text-sm text-white focus:outline-none focus:border-nexa-teal-400 focus:ring-1 focus:ring-nexa-teal-400"
            placeholder="40.7128"
            inputMode="decimal"
          />
        </div>
        <div className="col-span-1">
          <label htmlFor={lonId} className="text-[11px] text-slate-400 block mb-1">Longitude</label>
          <input
            id={lonId}
            value={lon}
            onChange={(e) => setLon(e.target.value)}
            className="w-full px-3 py-2 rounded-lg bg-nexa-navy-950 border border-nexa-navy-700 text-sm text-white focus:outline-none focus:border-nexa-teal-400 focus:ring-1 focus:ring-nexa-teal-400"
            placeholder="-74.0060"
            inputMode="decimal"
          />
        </div>
        <div className="col-span-1">
          <label htmlFor={radiusId} className="text-[11px] text-slate-400 block mb-1">Radius</label>
          <select
            id={radiusId}
            value={radiusMeters}
            onChange={(e) => setRadiusMeters(Number(e.target.value))}
            className="w-full px-3 py-2 rounded-lg bg-nexa-navy-950 border border-nexa-navy-700 text-sm text-white focus:outline-none focus:border-nexa-teal-400 focus:ring-1 focus:ring-nexa-teal-400"
          >
            {RADIUS_OPTIONS_METERS.map((r) => (
              <option key={r} value={r}>{r >= 1000 ? `${r / 1000} km` : `${r} m`}</option>
            ))}
          </select>
        </div>
        <div className="col-span-1">
          <label htmlFor={categoryId} className="text-[11px] text-slate-400 block mb-1">Category</label>
          <select
            id={categoryId}
            value={category}
            onChange={(e) => setCategory(e.target.value)}
            className="w-full px-3 py-2 rounded-lg bg-nexa-navy-950 border border-nexa-navy-700 text-sm text-white focus:outline-none focus:border-nexa-teal-400 focus:ring-1 focus:ring-nexa-teal-400"
          >
            {CATEGORIES.map((c) => (
              <option key={c} value={c}>{c === '' ? 'Any' : c}</option>
            ))}
          </select>
        </div>
        <div className="col-span-2 sm:col-span-1 flex gap-2">
          <button
            type="button"
            onClick={useMyLocation}
            disabled={locating}
            title="Requests your browser's location permission — only when you click this button"
            className="flex-1 inline-flex items-center justify-center space-x-1.5 px-3 py-2 rounded-lg text-xs font-semibold bg-nexa-navy-800 border border-nexa-navy-700 text-slate-200 hover:text-white disabled:opacity-60"
          >
            {locating ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <LocateFixed className="w-3.5 h-3.5" />}
            <span>Locate</span>
          </button>
          <button
            type="submit"
            className="flex-1 inline-flex items-center justify-center space-x-1.5 px-3 py-2 rounded-lg text-xs font-semibold bg-gradient-to-r from-nexa-blue-600 to-nexa-teal-600 text-white"
          >
            <MapPin className="w-3.5 h-3.5" />
            <span>Search</span>
          </button>
        </div>
      </form>

      <p className="text-[11px] text-slate-500 -mt-1">
        "Locate" only asks your browser for your current position when you click it — we use it just
        to fill in the coordinates above for this search. Nothing is requested automatically, and
        manual coordinate entry always works instead.
      </p>

      {locationNotice && (
        <div className="p-3 rounded-xl bg-amber-500/10 border border-amber-500/30 text-xs text-amber-200/90 flex items-start space-x-2.5">
          <AlertTriangle className="w-4 h-4 text-amber-400 flex-shrink-0 mt-0.5" />
          <span>{locationNotice}</span>
        </div>
      )}

      {state === 'loading' && (
        <div className="flex items-center space-x-2 text-sm text-slate-400 py-6 justify-center">
          <Loader2 className="w-4 h-4 animate-spin" />
          <span>Searching nearby service points...</span>
        </div>
      )}

      {state === 'validation-error' && (
        <div className="p-3.5 rounded-xl bg-amber-500/10 border border-amber-500/30 text-xs text-amber-200/90 flex items-start space-x-2.5">
          <AlertTriangle className="w-4 h-4 text-amber-400 flex-shrink-0 mt-0.5" />
          <span>{errorMessage}</span>
        </div>
      )}

      {state === 'unavailable' && (
        <div className="p-3.5 rounded-xl bg-red-500/10 border border-red-500/30 text-xs text-red-200/90 flex items-start space-x-2.5">
          <CloudOff className="w-4 h-4 text-red-400 flex-shrink-0 mt-0.5" />
          <div className="flex-1">
            <span>{errorMessage}</span>
            <div>
              <button
                type="button"
                onClick={() => runSearch(page)}
                className="mt-2 inline-flex items-center space-x-1.5 text-red-200 underline underline-offset-2 hover:text-white"
              >
                <RefreshCw className="w-3 h-3" />
                <span>Retry</span>
              </button>
            </div>
          </div>
        </div>
      )}

      {state === 'empty' && (
        <div className="flex items-center space-x-2 text-sm text-slate-400 py-6 justify-center">
          <SearchX className="w-4 h-4" />
          <span>No service points found within {radiusMeters >= 1000 ? `${radiusMeters / 1000}km` : `${radiusMeters}m`}.</span>
        </div>
      )}

      {state === 'success' && (
        <>
          <div className="space-y-2">
            {results.map((point) => (
              <div
                key={point.id}
                className="flex items-center justify-between p-3 rounded-xl bg-nexa-navy-950/60 border border-nexa-navy-800/80"
              >
                <div>
                  <p className="text-sm font-semibold text-white">{point.name}</p>
                  <p className="text-[11px] text-slate-400">{point.address || `${point.latitude.toFixed(4)}, ${point.longitude.toFixed(4)}`}</p>
                </div>
                <div className="text-right">
                  <span className="text-[10px] px-2 py-0.5 rounded-full font-semibold bg-nexa-navy-800 text-slate-300 border border-nexa-navy-700">
                    {point.type}
                  </span>
                  <p className="text-xs text-nexa-teal-400 font-mono mt-1">{formatDistance(point.distanceMeters)}</p>
                </div>
              </div>
            ))}
          </div>

          {totalPages > 1 && (
            <div className="flex items-center justify-between pt-2 border-t border-nexa-navy-800/80">
              <span className="text-xs text-slate-400">Page {page + 1} of {totalPages}</span>
              <div className="flex items-center space-x-2">
                <button
                  onClick={() => runSearch(page - 1)}
                  disabled={page === 0}
                  aria-label="Previous page"
                  className="p-1.5 rounded-lg bg-nexa-navy-800 border border-nexa-navy-700 text-slate-300 hover:text-white disabled:opacity-40"
                >
                  <ChevronLeft className="w-4 h-4" />
                </button>
                <button
                  onClick={() => runSearch(page + 1)}
                  disabled={page + 1 >= totalPages}
                  aria-label="Next page"
                  className="p-1.5 rounded-lg bg-nexa-navy-800 border border-nexa-navy-700 text-slate-300 hover:text-white disabled:opacity-40"
                >
                  <ChevronRight className="w-4 h-4" />
                </button>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
};
