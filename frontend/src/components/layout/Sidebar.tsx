import React from 'react';
import { Link, useLocation } from 'react-router-dom';
import {
  LayoutDashboard,
  ArrowLeftRight,
  History,
  Compass,
  ShieldAlert,
  Settings,
  Database,
  User,
  LogOut
} from 'lucide-react';
import { useAuth } from '../../context/AuthContext';

interface SidebarProps {
  onClose?: () => void;
}

export const Sidebar: React.FC<SidebarProps> = ({ onClose }) => {
  const location = useLocation();
  const { user, isAuthenticated, logout } = useAuth();

  const navigation = [
    { name: 'Dashboard', href: '/dashboard', icon: LayoutDashboard },
    { name: 'Transfers', href: '/dashboard#transfers', icon: ArrowLeftRight, badge: 'Phase 3' },
    { name: 'Transactions', href: '/dashboard#transactions', icon: History, badge: 'Phase 6' },
    { name: 'Discovery', href: '/dashboard#discovery', icon: Compass, badge: 'Geohash' },
    { name: 'Ledger Audit', href: '/dashboard#audit', icon: ShieldAlert, badge: 'Admin' },
    { name: 'Settings', href: '/dashboard#settings', icon: Settings },
  ];

  return (
    <aside className="w-64 bg-nexa-navy-900 border-r border-nexa-navy-800 flex flex-col h-full min-h-[calc(100vh-4rem)]">
      {/* User Status Header inside Sidebar */}
      {isAuthenticated && user && (
        <div className="p-4 border-b border-nexa-navy-800 bg-nexa-navy-950/40">
          <div className="flex items-center space-x-3">
            <div className="w-9 h-9 rounded-xl bg-gradient-to-tr from-nexa-blue-600 to-nexa-teal-500 text-white flex items-center justify-center font-bold text-sm shadow-md">
              {user.firstName ? user.firstName.charAt(0).toUpperCase() : <User className="w-4 h-4" />}
            </div>
            <div className="overflow-hidden">
              <p className="text-xs font-semibold text-white truncate">
                {user.firstName} {user.lastName}
              </p>
              <p className="text-[10px] text-slate-400 truncate">{user.email}</p>
            </div>
          </div>
          <div className="mt-2.5 pt-2 border-t border-nexa-navy-800/80 flex items-center justify-between text-[11px]">
            <span className="text-slate-400">Wallet:</span>
            <span className="font-mono text-nexa-teal-400 font-semibold">{user.walletNumber}</span>
          </div>
        </div>
      )}

      {/* Navigation Group */}
      <div className="flex-1 py-6 px-4 space-y-1.5 overflow-y-auto">
        <p className="px-3 text-xs font-semibold uppercase tracking-wider text-slate-400 mb-3">
          Platform Navigation
        </p>
        {navigation.map((item) => {
          const isActive = location.pathname === item.href;
          const Icon = item.icon;

          return (
            <Link
              key={item.name}
              to={item.href}
              onClick={onClose}
              className={`flex items-center justify-between px-3.5 py-2.5 rounded-xl text-sm font-medium transition-all duration-150 ${
                isActive
                  ? 'bg-nexa-blue-600/15 text-nexa-teal-400 border border-nexa-teal-500/30 shadow-sm'
                  : 'text-slate-300 hover:text-white hover:bg-nexa-navy-800/60'
              }`}
            >
              <div className="flex items-center space-x-3">
                <Icon className={`w-4 h-4 ${isActive ? 'text-nexa-teal-400' : 'text-slate-400'}`} />
                <span>{item.name}</span>
              </div>
              {item.badge && (
                <span className="text-[10px] px-2 py-0.5 rounded-full font-semibold bg-nexa-navy-800 text-slate-300 border border-nexa-navy-700">
                  {item.badge}
                </span>
              )}
            </Link>
          );
        })}
      </div>

      {/* Ledger & Architecture Info Card */}
      <div className="p-4 border-t border-nexa-navy-800 space-y-3">
        <div className="p-3.5 rounded-xl bg-nexa-navy-950/80 border border-nexa-navy-800/80">
          <div className="flex items-center space-x-2 text-xs font-semibold text-slate-200 mb-1.5">
            <Database className="w-4 h-4 text-nexa-blue-400" />
            <span>Double-Entry Core</span>
          </div>
          <p className="text-[11px] text-slate-400 leading-relaxed">
            PostgreSQL ACID ledger. Debits and credits enforced with row locks.
          </p>
        </div>

        {isAuthenticated && (
          <button
            onClick={() => {
              logout();
              if (onClose) onClose();
            }}
            className="w-full flex items-center justify-center space-x-2 py-2 px-3 rounded-xl text-xs font-semibold bg-nexa-navy-950 border border-nexa-navy-800 text-red-400 hover:bg-red-500/10 hover:border-red-500/30 transition-colors"
          >
            <LogOut className="w-3.5 h-3.5" />
            <span>Sign Out</span>
          </button>
        )}
      </div>
    </aside>
  );
};
