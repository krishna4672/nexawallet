import React, { useState } from 'react';
import { Link, useNavigate, useLocation } from 'react-router-dom';
import { Mail, Lock, Eye, EyeOff, ArrowRight, Shield, AlertCircle, Loader2 } from 'lucide-react';
import { loginUser } from '../services/api';
import { useAuth } from '../context/AuthContext';
import axios from 'axios';

export const LoginPage: React.FC = () => {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const destination = (location.state as { from?: { pathname?: string } })?.from?.pathname || '/dashboard';

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setErrorMessage(null);

    // Client-side validation
    if (!email.trim() || !password) {
      setErrorMessage('Please provide both email and password.');
      return;
    }

    try {
      setIsLoading(true);
      const authData = await loginUser({
        email: email.trim().toLowerCase(),
        password,
      });

      // Save token in memory and store user state
      login(authData);

      // Navigate to destination
      navigate(destination, { replace: true });
    } catch (err: unknown) {
      if (axios.isAxiosError(err) && err.response) {
        const backendMessage = err.response.data?.message;
        setErrorMessage(backendMessage || 'Invalid email or password.');
      } else {
        setErrorMessage('Unable to connect to the authentication service. Please verify backend is running on port 8080.');
      }
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="min-h-[calc(100vh-4rem)] flex items-center justify-center p-4">
      <div className="w-full max-w-md space-y-6">
        {/* Card Header */}
        <div className="text-center space-y-2">
          <div className="inline-flex items-center justify-center w-12 h-12 rounded-2xl bg-gradient-to-tr from-nexa-blue-600 to-nexa-teal-500 shadow-lg shadow-nexa-blue-500/20 text-white mb-2">
            <Shield className="w-6 h-6" />
          </div>
          <h1 className="text-2xl sm:text-3xl font-bold tracking-tight text-white">
            Sign In to NexaWallet
          </h1>
          <p className="text-sm text-slate-400">
            Secure digital wallet access with stateless JWT verification
          </p>
        </div>

        {/* Dynamic Error Alert */}
        {errorMessage && (
          <div className="p-3.5 rounded-xl bg-red-500/10 border border-red-500/30 text-xs text-red-300 flex items-start space-x-2.5 animate-fadeIn">
            <AlertCircle className="w-4 h-4 text-red-400 flex-shrink-0 mt-0.5" />
            <div>
              <p className="font-semibold text-red-300">Authentication Failed</p>
              <p className="text-[11px] text-red-200/90 mt-0.5">{errorMessage}</p>
            </div>
          </div>
        )}

        {/* Login Form */}
        <div className="p-8 rounded-2xl bg-nexa-navy-900/80 border border-nexa-navy-800 backdrop-blur-xl shadow-2xl">
          <form onSubmit={handleSubmit} className="space-y-5">
            {/* Email Field */}
            <div className="space-y-1.5">
              <label className="text-xs font-semibold uppercase tracking-wider text-slate-300">
                Email Address
              </label>
              <div className="relative">
                <Mail className="w-4 h-4 text-slate-400 absolute left-3.5 top-3.5" />
                <input
                  type="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  placeholder="alex.hunter@example.com"
                  required
                  disabled={isLoading}
                  className="w-full pl-10 pr-4 py-2.5 bg-nexa-navy-950 border border-nexa-navy-700 rounded-xl text-sm text-white placeholder-slate-500 focus:outline-none focus:border-nexa-teal-400 focus:ring-1 focus:ring-nexa-teal-400 transition-colors disabled:opacity-60"
                />
              </div>
            </div>

            {/* Password Field */}
            <div className="space-y-1.5">
              <div className="flex justify-between items-center">
                <label className="text-xs font-semibold uppercase tracking-wider text-slate-300">
                  Password
                </label>
              </div>
              <div className="relative">
                <Lock className="w-4 h-4 text-slate-400 absolute left-3.5 top-3.5" />
                <input
                  type={showPassword ? 'text' : 'password'}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="••••••••••••"
                  required
                  disabled={isLoading}
                  className="w-full pl-10 pr-10 py-2.5 bg-nexa-navy-950 border border-nexa-navy-700 rounded-xl text-sm text-white placeholder-slate-500 focus:outline-none focus:border-nexa-teal-400 focus:ring-1 focus:ring-nexa-teal-400 transition-colors disabled:opacity-60"
                />
                <button
                  type="button"
                  onClick={() => setShowPassword(!showPassword)}
                  className="absolute right-3.5 top-3 text-slate-400 hover:text-slate-200"
                >
                  {showPassword ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                </button>
              </div>
            </div>

            {/* In-Memory Security Note */}
            <div className="p-2.5 rounded-lg bg-nexa-navy-950/60 border border-nexa-navy-800 text-[11px] text-slate-400 flex items-center justify-between">
              <span>Token Storage:</span>
              <span className="text-nexa-teal-400 font-mono font-medium">In-Memory (XSS Safe)</span>
            </div>

            {/* Submit Button */}
            <button
              type="submit"
              disabled={isLoading}
              className="w-full inline-flex items-center justify-center space-x-2 py-3 px-4 rounded-xl font-semibold bg-gradient-to-r from-nexa-blue-600 to-nexa-teal-600 text-white shadow-lg shadow-nexa-blue-600/25 hover:from-nexa-blue-500 hover:to-nexa-teal-500 transition-all duration-200 disabled:opacity-70 disabled:cursor-not-allowed"
            >
              {isLoading ? (
                <>
                  <Loader2 className="w-4 h-4 animate-spin text-white" />
                  <span>Authenticating...</span>
                </>
              ) : (
                <>
                  <span>Sign In</span>
                  <ArrowRight className="w-4 h-4" />
                </>
              )}
            </button>
          </form>

          {/* Switch to Register */}
          <div className="mt-6 pt-5 border-t border-nexa-navy-800 text-center text-xs text-slate-400">
            <span>Don't have an account yet? </span>
            <Link to="/register" className="text-nexa-teal-400 font-semibold hover:underline">
              Create an account
            </Link>
          </div>
        </div>
      </div>
    </div>
  );
};
