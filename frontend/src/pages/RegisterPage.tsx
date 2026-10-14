import React, { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Mail, Lock, User, Eye, EyeOff, ArrowRight, ShieldCheck, AlertCircle, Loader2 } from 'lucide-react';
import { registerUser } from '../services/api';
import { useAuth } from '../context/AuthContext';
import axios from 'axios';

export const RegisterPage: React.FC = () => {
  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const { login } = useAuth();
  const navigate = useNavigate();

  // Password strength calculation
  const getPasswordStrength = () => {
    if (!password) return { label: 'Empty', score: 0, color: 'bg-slate-700' };
    let score = 0;
    if (password.length >= 8) score++;
    if (/[A-Z]/.test(password)) score++;
    if (/[0-9]/.test(password)) score++;
    if (/[^A-Za-z0-9]/.test(password)) score++;

    if (score <= 1) return { label: 'Weak (min 8 chars)', score: 1, color: 'bg-red-500' };
    if (score === 2 || score === 3) return { label: 'Medium', score: 2, color: 'bg-amber-500' };
    return { label: 'Strong (Fintech Grade)', score: 3, color: 'bg-emerald-500' };
  };

  const strength = getPasswordStrength();

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setErrorMessage(null);

    // Client-side validation
    if (!firstName.trim() || !lastName.trim()) {
      setErrorMessage('Please provide both your first and last name.');
      return;
    }
    if (!email.trim()) {
      setErrorMessage('Please provide a valid email address.');
      return;
    }
    if (password.length < 8) {
      setErrorMessage('Password must be at least 8 characters in length.');
      return;
    }
    if (password !== confirmPassword) {
      setErrorMessage('Passwords do not match. Please re-enter.');
      return;
    }

    try {
      setIsLoading(true);
      const authData = await registerUser({
        firstName: firstName.trim(),
        lastName: lastName.trim(),
        email: email.trim().toLowerCase(),
        password,
      });

      // Save token in memory and store user state
      login(authData);

      // Navigate to dashboard with newly created wallet
      navigate('/dashboard', { replace: true });
    } catch (err: unknown) {
      if (axios.isAxiosError(err) && err.response) {
        const backendMessage = err.response.data?.message;
        setErrorMessage(backendMessage || 'Registration failed. Please review your details.');
      } else {
        setErrorMessage('Unable to connect to registration service. Please verify backend is running on port 8080.');
      }
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="min-h-[calc(100vh-4rem)] flex items-center justify-center p-4">
      <div className="w-full max-w-lg space-y-6">
        {/* Header */}
        <div className="text-center space-y-2">
          <div className="inline-flex items-center justify-center w-12 h-12 rounded-2xl bg-gradient-to-tr from-nexa-blue-600 to-nexa-teal-500 shadow-lg shadow-nexa-blue-500/20 text-white mb-2">
            <ShieldCheck className="w-6 h-6" />
          </div>
          <h1 className="text-2xl sm:text-3xl font-bold tracking-tight text-white">
            Open Your NexaWallet Account
          </h1>
          <p className="text-sm text-slate-400">
            Automated USD wallet generation with double-entry ledger allocation
          </p>
        </div>

        {/* Dynamic Error Alert */}
        {errorMessage && (
          <div className="p-3.5 rounded-xl bg-red-500/10 border border-red-500/30 text-xs text-red-300 flex items-start space-x-2.5 animate-fadeIn">
            <AlertCircle className="w-4 h-4 text-red-400 flex-shrink-0 mt-0.5" />
            <div>
              <p className="font-semibold text-red-300">Registration Error</p>
              <p className="text-[11px] text-red-200/90 mt-0.5">{errorMessage}</p>
            </div>
          </div>
        )}

        {/* Register Form */}
        <div className="p-8 rounded-2xl bg-nexa-navy-900/80 border border-nexa-navy-800 backdrop-blur-xl shadow-2xl">
          <form onSubmit={handleSubmit} className="space-y-4">
            {/* Name Fields (2 Columns) */}
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <div className="space-y-1.5">
                <label className="text-xs font-semibold uppercase tracking-wider text-slate-300">
                  First Name
                </label>
                <div className="relative">
                  <User className="w-4 h-4 text-slate-400 absolute left-3.5 top-3.5" />
                  <input
                    type="text"
                    value={firstName}
                    onChange={(e) => setFirstName(e.target.value)}
                    placeholder="Alex"
                    required
                    disabled={isLoading}
                    className="w-full pl-10 pr-4 py-2.5 bg-nexa-navy-950 border border-nexa-navy-700 rounded-xl text-sm text-white placeholder-slate-500 focus:outline-none focus:border-nexa-teal-400 focus:ring-1 focus:ring-nexa-teal-400 transition-colors disabled:opacity-60"
                  />
                </div>
              </div>

              <div className="space-y-1.5">
                <label className="text-xs font-semibold uppercase tracking-wider text-slate-300">
                  Last Name
                </label>
                <div className="relative">
                  <User className="w-4 h-4 text-slate-400 absolute left-3.5 top-3.5" />
                  <input
                    type="text"
                    value={lastName}
                    onChange={(e) => setLastName(e.target.value)}
                    placeholder="Hunter"
                    required
                    disabled={isLoading}
                    className="w-full pl-10 pr-4 py-2.5 bg-nexa-navy-950 border border-nexa-navy-700 rounded-xl text-sm text-white placeholder-slate-500 focus:outline-none focus:border-nexa-teal-400 focus:ring-1 focus:ring-nexa-teal-400 transition-colors disabled:opacity-60"
                  />
                </div>
              </div>
            </div>

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
              <label className="text-xs font-semibold uppercase tracking-wider text-slate-300">
                Password
              </label>
              <div className="relative">
                <Lock className="w-4 h-4 text-slate-400 absolute left-3.5 top-3.5" />
                <input
                  type={showPassword ? 'text' : 'password'}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="Create a strong password (min 8 chars)"
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

              {/* Password Strength Meter */}
              {password && (
                <div className="pt-1.5 space-y-1">
                  <div className="flex justify-between items-center text-[11px]">
                    <span className="text-slate-400">Password strength:</span>
                    <span className="font-semibold text-slate-200">{strength.label}</span>
                  </div>
                  <div className="h-1.5 w-full bg-nexa-navy-950 rounded-full overflow-hidden flex gap-1">
                    <div
                      className={`h-full flex-1 rounded-full ${
                        strength.score >= 1 ? strength.color : 'bg-slate-700'
                      }`}
                    />
                    <div
                      className={`h-full flex-1 rounded-full ${
                        strength.score >= 2 ? strength.color : 'bg-slate-700'
                      }`}
                    />
                    <div
                      className={`h-full flex-1 rounded-full ${
                        strength.score >= 3 ? strength.color : 'bg-slate-700'
                      }`}
                    />
                  </div>
                </div>
              )}
            </div>

            {/* Confirm Password Field */}
            <div className="space-y-1.5">
              <label className="text-xs font-semibold uppercase tracking-wider text-slate-300">
                Confirm Password
              </label>
              <div className="relative">
                <Lock className="w-4 h-4 text-slate-400 absolute left-3.5 top-3.5" />
                <input
                  type={showPassword ? 'text' : 'password'}
                  value={confirmPassword}
                  onChange={(e) => setConfirmPassword(e.target.value)}
                  placeholder="Repeat your password"
                  required
                  disabled={isLoading}
                  className="w-full pl-10 pr-4 py-2.5 bg-nexa-navy-950 border border-nexa-navy-700 rounded-xl text-sm text-white placeholder-slate-500 focus:outline-none focus:border-nexa-teal-400 focus:ring-1 focus:ring-nexa-teal-400 transition-colors disabled:opacity-60"
                />
              </div>
            </div>

            {/* Submit Button */}
            <button
              type="submit"
              disabled={isLoading}
              className="w-full inline-flex items-center justify-center space-x-2 py-3 px-4 rounded-xl font-semibold bg-gradient-to-r from-nexa-blue-600 to-nexa-teal-600 text-white shadow-lg shadow-nexa-blue-600/25 hover:from-nexa-blue-500 hover:to-nexa-teal-500 transition-all duration-200 mt-2 disabled:opacity-70 disabled:cursor-not-allowed"
            >
              {isLoading ? (
                <>
                  <Loader2 className="w-4 h-4 animate-spin text-white" />
                  <span>Registering Account & Wallet...</span>
                </>
              ) : (
                <>
                  <span>Create Account</span>
                  <ArrowRight className="w-4 h-4" />
                </>
              )}
            </button>
          </form>

          {/* Switch to Sign In */}
          <div className="mt-6 pt-5 border-t border-nexa-navy-800 text-center text-xs text-slate-400">
            <span>Already have an account? </span>
            <Link to="/login" className="text-nexa-teal-400 font-semibold hover:underline">
              Sign In
            </Link>
          </div>
        </div>
      </div>
    </div>
  );
};
