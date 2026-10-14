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
