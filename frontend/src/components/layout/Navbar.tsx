import React, { useEffect, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { Wallet, Activity, ExternalLink, ArrowRight, LogOut, User as UserIcon } from 'lucide-react';
import { checkSystemHealth, type SystemHealthData } from '../../services/api';
import { useAuth } from '../../context/AuthContext';

export const Navbar: React.FC = () => {
  const [health, setHealth] = useState<SystemHealthData | null>(null);
  const [healthError, setHealthError] = useState(false);
  const { user, isAuthenticated, logout } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();

  useEffect(() => {
    checkSystemHealth()
      .then((data) => {
        setHealth(data);
        setHealthError(false);
      })
      .catch(() => {
        setHealthError(true);
      });
  }, []);

  const handleLogout = () => {
    logout();
    navigate('/');
  };

  return (
    <nav className="sticky top-0 z-50 backdrop-blur-md bg-nexa-navy-950/80 border-b border-nexa-navy-800">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="flex items-center justify-between h-16">
          {/* Brand Logo */}
          <Link to="/" className="flex items-center space-x-3 group">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-tr from-nexa-blue-600 via-nexa-blue-500 to-nexa-teal-400 flex items-center justify-center shadow-lg shadow-nexa-blue-500/20 group-hover:scale-105 transition-transform duration-200">
              <Wallet className="w-5 h-5 text-white" />
            </div>
            <div>
              <div className="flex items-center space-x-1.5">
                <span className="text-xl font-bold tracking-tight text-white font-sans">Nexa</span>
                <span className="text-xl font-bold tracking-tight text-nexa-teal-400 font-sans">Wallet</span>
              </div>
              <span className="text-[10px] tracking-wider uppercase font-semibold text-nexa-slate-400 block -mt-1">
                Enterprise Core
              </span>
            </div>
          </Link>

          {/* Navigation Links */}
          <div className="hidden md:flex items-center space-x-6 text-sm font-medium">
            <Link
              to="/"
              className={`transition-colors ${
                location.pathname === '/' ? 'text-nexa-teal-400 font-semibold' : 'text-slate-300 hover:text-white'
              }`}
            >
              Overview
            </Link>
            <Link
              to="/dashboard"
              className={`transition-colors ${
                location.pathname === '/dashboard' ? 'text-nexa-teal-400 font-semibold' : 'text-slate-300 hover:text-white'
              }`}
            >
              Dashboard
            </Link>
            <a
              href="http://localhost:8080/swagger-ui.html"
              target="_blank"
              rel="noopener noreferrer"
              className="flex items-center space-x-1 text-slate-300 hover:text-white transition-colors"
            >
              <span>API Docs</span>
              <ExternalLink className="w-3.5 h-3.5 text-slate-400" />
            </a>
          </div>

          {/* Right Action / Status Area */}
          <div className="flex items-center space-x-4">
            {/* Live Backend Connection Indicator */}
            <div className="hidden sm:flex items-center space-x-2 px-3 py-1.5 rounded-full text-xs font-medium bg-nexa-navy-900 border border-nexa-navy-800">
              <Activity
                className={`w-3.5 h-3.5 ${
                  health?.status === 'UP'
                    ? 'text-emerald-400 animate-pulse'
                    : healthError
                    ? 'text-amber-400'
                    : 'text-slate-400'
                }`}
              />
              <span className="text-slate-300">
                {health?.status === 'UP' ? 'Backend Live' : 'Backend Ready (Port 8080)'}
              </span>
            </div>

            {/* Authenticated User Status vs Unauthenticated Actions */}
            {isAuthenticated && user ? (
              <div className="flex items-center space-x-3">
                <div className="hidden sm:flex items-center space-x-2 px-3 py-1.5 rounded-xl bg-nexa-navy-900 border border-nexa-navy-800 text-xs">
                  <div className="w-6 h-6 rounded-lg bg-nexa-blue-600/30 text-nexa-blue-400 flex items-center justify-center font-bold">
                    <UserIcon className="w-3.5 h-3.5" />
                  </div>
                  <div className="text-left">
                    <span className="font-semibold text-white block leading-tight">{user.firstName}</span>
                    <span className="text-[10px] text-nexa-teal-400 font-mono block leading-tight">{user.walletNumber}</span>
                  </div>
                </div>

                <button
                  onClick={handleLogout}
                  title="Sign Out"
                  className="inline-flex items-center space-x-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold bg-nexa-navy-900 border border-nexa-navy-700 text-slate-300 hover:text-white hover:bg-red-500/10 hover:border-red-500/30 hover:text-red-300 transition-colors"
                >
                  <LogOut className="w-3.5 h-3.5" />
                  <span className="hidden sm:inline">Sign Out</span>
                </button>
              </div>
            ) : (
              <>
                <Link
                  to="/login"
                  className="text-sm font-medium text-slate-300 hover:text-white transition-colors px-3 py-2"
                >
                  Sign In
                </Link>
                <Link
                  to="/register"
                  className="inline-flex items-center space-x-1.5 px-4 py-2 rounded-lg text-sm font-semibold bg-gradient-to-r from-nexa-blue-600 to-nexa-teal-600 text-white shadow-md shadow-nexa-blue-600/25 hover:from-nexa-blue-500 hover:to-nexa-teal-500 transition-all duration-200"
                >
                  <span>Get Started</span>
                  <ArrowRight className="w-4 h-4" />
                </Link>
              </>
            )}
          </div>
        </div>
      </div>
    </nav>
  );
};
