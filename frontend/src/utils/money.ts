/**
 * Display-only currency formatting. This never performs arithmetic on monetary values — amounts
 * always come from the backend (PostgreSQL/BigDecimal) already computed; the frontend only ever
 * formats a number it already received for display, and sends whatever the user typed straight
 * through to the backend for it to parse and validate authoritatively.
 */
export const formatCurrency = (amount: number, currency = 'USD'): string => {
  try {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency,
      minimumFractionDigits: 2,
      maximumFractionDigits: 4,
    }).format(amount);
  } catch {
    return `${amount.toFixed(4)} ${currency}`;
  }
};

export const formatDistance = (meters: number): string =>
  meters >= 1000 ? `${(meters / 1000).toFixed(2)} km` : `${Math.round(meters)} m`;

export const formatDateTime = (iso: string): string => {
  try {
    return new Date(iso).toLocaleString('en-US', {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  } catch {
    return iso;
  }
};

/**
 * Parses a user-typed amount string for client-side validation only (positive, finite, at most
 * 4 decimal places). Returns null for anything invalid rather than silently coercing it — the
 * backend's own validation remains authoritative regardless of what this returns.
 */
export const parsePositiveAmount = (raw: string): number | null => {
  const trimmed = raw.trim();
  if (!/^\d+(\.\d{1,4})?$/.test(trimmed)) {
    return null;
  }
  const value = Number(trimmed);
  if (!Number.isFinite(value) || value <= 0) {
    return null;
  }
  return value;
};
