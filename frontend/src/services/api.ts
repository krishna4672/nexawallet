import axios from 'axios';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1';

export const apiClient = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
  timeout: 10000,
});

export interface SystemHealthData {
  status: string;
  application: string;
  version: string;
  environment: string;
  timestamp: string;
  components: Record<string, string>;
}

export interface UserData {
  id: string;
  email: string;
  firstName: string;
  lastName: string;
  role: string;
  walletNumber: string;
  walletBalance: number;
  currency: string;
  createdAt: string;
}

export interface AuthResponseData {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  user: UserData;
}

export interface RegisterPayload {
  firstName: string;
  lastName: string;
  email: string;
  password: string;
}

export interface LoginPayload {
  email: string;
  password: string;
}

// System Health Check
export const checkSystemHealth = async (): Promise<SystemHealthData> => {
  const response = await apiClient.get<SystemHealthData>('/health');
  return response.data;
};

// User Registration API
export const registerUser = async (payload: RegisterPayload): Promise<AuthResponseData> => {
  const response = await apiClient.post<AuthResponseData>('/auth/register', payload);
  return response.data;
};

// User Login API
export const loginUser = async (payload: LoginPayload): Promise<AuthResponseData> => {
  const response = await apiClient.post<AuthResponseData>('/auth/login', payload);
  return response.data;
};

// Fetch Current Authenticated Profile
export const fetchCurrentUser = async (token: string): Promise<UserData> => {
  const response = await apiClient.get<UserData>('/auth/me', {
    headers: {
      Authorization: `Bearer ${token}`,
    },
  });
  return response.data;
};

// Phase 6: Nearby Service Point Discovery (public endpoint — no auth required)
export interface NearbyServicePoint {
  id: string;
  name: string;
  type: string;
  latitude: number;
  longitude: number;
  address: string | null;
  distanceMeters: number;
}

export interface NearbySearchResult {
  content: NearbyServicePoint[];
  page: number;
  size: number;
  totalElements: number;
}

export interface NearbySearchParams {
  lat: number;
  lon: number;
  radiusMeters?: number;
  category?: string;
  page?: number;
  size?: number;
}

export const fetchNearbyServicePoints = async (params: NearbySearchParams): Promise<NearbySearchResult> => {
  const response = await apiClient.get<NearbySearchResult>('/service-points/nearby', { params });
  return response.data;
};

// ─────────────────────────────────────────────────────────────────────────
// Wallet, deposit, transfer, and transaction history (Phase 3 backend APIs)
// ─────────────────────────────────────────────────────────────────────────

export interface WalletData {
  id: string;
  walletNumber: string;
  balance: number;
  currency: string;
  status: string;
  createdAt: string;
  updatedAt: string;
}

export interface TransactionData {
  transactionId: string;
  referenceCode: string;
  type: string;
  status: string;
  amount: number;
  currency: string;
  sourceWalletNumber: string | null;
  targetWalletNumber: string | null;
  description: string | null;
  createdAt: string;
}

/** Shape of a Spring Data {@code Page<T>} JSON response — only the fields the UI actually uses. */
export interface PageData<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number; // current page index (0-based)
  size: number;
  first: boolean;
  last: boolean;
}

export interface DepositPayload {
  amount: number;
  description?: string;
}

export interface TransferPayload {
  targetWalletNumber: string;
  amount: number;
  description?: string;
}

export const fetchWallet = async (): Promise<WalletData> => {
  const response = await apiClient.get<WalletData>('/wallet/me');
  return response.data;
};

/** Simulated deposit only — no real payment provider is connected. */
export const depositFunds = async (payload: DepositPayload): Promise<TransactionData> => {
  const response = await apiClient.post<TransactionData>('/wallet/deposit', payload);
  return response.data;
};

/**
 * Initiates a P2P transfer. `idempotencyKey` must be a fresh UUID for a genuinely new transfer,
 * and the SAME key reused for a retry of an uncertain-outcome attempt of that same transfer —
 * the caller (TransferForm) owns that distinction; this function just forwards whatever key it's given.
 */
export const transferFunds = async (payload: TransferPayload, idempotencyKey: string): Promise<TransactionData> => {
  const response = await apiClient.post<TransactionData>('/transfers', payload, {
    headers: { 'Idempotency-Key': idempotencyKey },
  });
  return response.data;
};

export const fetchTransactions = async (page = 0, size = 10): Promise<PageData<TransactionData>> => {
  const response = await apiClient.get<PageData<TransactionData>>('/wallet/transactions', {
    params: { page, size },
  });
  return response.data;
};

/**
 * Extracts a safe, user-facing message from an API error — never a raw stack trace or internal
 * detail. Falls back to a status-appropriate generic message when the backend didn't supply one
 * (e.g. a network failure, or a 503/429 whose body the caller doesn't need to parse further).
 */
export const extractApiErrorMessage = (err: unknown, fallback: string): string => {
  if (axios.isAxiosError(err)) {
    if (err.response) {
      const status = err.response.status;
      const backendMessage: string | undefined = err.response.data?.message;
      if (status === 429) {
        const retryAfter = err.response.headers?.['retry-after'];
        return retryAfter
          ? `Too many requests. Please try again in ${retryAfter} second(s).`
          : backendMessage || 'Too many requests. Please slow down and try again shortly.';
      }
      if (status === 503) {
        return backendMessage || 'This service is temporarily unavailable. Please try again shortly.';
      }
      if (status === 403) {
        return backendMessage || 'You do not have permission to perform this action.';
      }
      return backendMessage || fallback;
    }
    return 'Unable to reach the server. Please check your connection and try again.';
  }
  return fallback;
};
