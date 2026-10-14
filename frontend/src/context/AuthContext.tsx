import React, { createContext, useContext, useState, useMemo, useEffect } from 'react';
import { apiClient, type AuthResponseData, type UserData } from '../services/api';

/**
 * Authentication context state interface.
 * Security notice: The JWT access token is stored strictly in JavaScript runtime memory
 * (React state) rather than browser localStorage/sessionStorage.
 *
 * Security Trade-off:
 * - Advantage: Complete immunity against token theft via Cross-Site Scripting (XSS) attacks.
 * - Trade-off: Hard browser refresh reinitializes memory; users re-authenticate or use refresh tokens in future phases.
 */
interface AuthContextType {
  token: string | null;
  user: UserData | null;
  isAuthenticated: boolean;
  login: (data: AuthResponseData) => void;
  logout: () => void;
  updateUser: (updatedUser: UserData) => void;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  // Stored strictly in-memory (no localStorage)
  const [token, setToken] = useState<string | null>(null);
  const [user, setUser] = useState<UserData | null>(null);

  // Synchronize Axios authorization header with the in-memory token
  useEffect(() => {
    const interceptorId = apiClient.interceptors.request.use((config) => {
      if (token) {
        config.headers.Authorization = `Bearer ${token}`;
      } else {
        delete config.headers.Authorization;
      }
      return config;
    });

    return () => {
      apiClient.interceptors.request.eject(interceptorId);
    };
  }, [token]);

  const login = (data: AuthResponseData) => {
    setToken(data.accessToken);
    setUser(data.user);
  };

  const logout = () => {
    setToken(null);
    setUser(null);
    delete apiClient.defaults.headers.common['Authorization'];
  };

  const updateUser = (updatedUser: UserData) => {
    setUser(updatedUser);
  };

  const value = useMemo(
    () => ({
      token,
      user,
      isAuthenticated: Boolean(token && user),
      login,
      logout,
      updateUser,
    }),
    [token, user]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
};

export const useAuth = (): AuthContextType => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
};
