import React, { useState } from 'react';
import {
  Wallet,
  ArrowUpRight,
  ArrowDownLeft,
  ShieldCheck,
  Layers,
  TrendingUp,
  AlertCircle,
  CheckCircle2,
  Copy,
  ExternalLink
} from 'lucide-react';
import {
  AreaChart,
  Area,
  XAxis,
  YAxis,
  Tooltip,
  ResponsiveContainer
} from 'recharts';
import { useAuth } from '../context/AuthContext';

const mockAnalyticsData = [
  { day: 'Mon', spending: 240, income: 400 },
  { day: 'Tue', spending: 180, income: 150 },
  { day: 'Wed', spending: 320, income: 600 },
  { day: 'Thu', spending: 110, income: 200 },
  { day: 'Fri', spending: 480, income: 850 },
  { day: 'Sat', spending: 210, income: 100 },
  { day: 'Sun', spending: 150, income: 300 },
];

export const DashboardPage: React.FC = () => {
  const { user } = useAuth();
  const [showTransferPreviewModal, setShowTransferPreviewModal] = useState(false);
  const [copied, setCopied] = useState(false);

  const walletNumber = user?.walletNumber || 'NX-PENDING-ALLOC';
  const balance = user?.walletBalance !== undefined
    ? Number(user.walletBalance).toLocaleString('en-US', { minimumFractionDigits: 4, maximumFractionDigits: 4 })
    : '0.0000';

  const copyWallet = () => {
    navigator.clipboard.writeText(walletNumber);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="p-4 sm:p-8 max-w-7xl mx-auto space-y-8">
      {/* Dashboard Top Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <div className="flex items-center space-x-2">
            <h1 className="text-2xl sm:text-3xl font-bold text-white tracking-tight">
              Customer Command Center
            </h1>
            <span className="px-2.5 py-0.5 rounded-full text-[11px] font-mono font-semibold bg-emerald-500/10 border border-emerald-500/30 text-emerald-400">
              Authenticated
            </span>
          </div>
          <p className="text-sm text-slate-400 mt-1">
            Welcome back, <strong className="text-white">{user?.firstName} {user?.lastName}</strong> ({user?.email})
          </p>
        </div>

        {/* Quick Actions */}
        <div className="flex items-center space-x-3">
          <button
            onClick={() => setShowTransferPreviewModal(true)}
            className="inline-flex items-center space-x-2 px-4 py-2.5 rounded-xl text-sm font-semibold bg-gradient-to-r from-nexa-blue-600 to-nexa-teal-600 text-white shadow-lg shadow-nexa-blue-600/20 hover:from-nexa-blue-500 hover:to-nexa-teal-500 transition-all"
          >
            <ArrowUpRight className="w-4 h-4" />
            <span>Send Transfer</span>
          </button>
          <button
            onClick={() => setShowTransferPreviewModal(true)}
            className="inline-flex items-center space-x-2 px-4 py-2.5 rounded-xl text-sm font-semibold bg-nexa-navy-900 border border-nexa-navy-700 text-slate-200 hover:text-white hover:bg-nexa-navy-800 transition-all"
          >
            <ArrowDownLeft className="w-4 h-4 text-nexa-teal-400" />
            <span>Simulate Deposit</span>
          </button>
        </div>
      </div>

      {/* Phase Status Banner */}
      <div className="p-4 rounded-2xl bg-nexa-navy-900/90 border border-nexa-navy-800 text-xs text-slate-300 flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3 shadow-md">
        <div className="flex items-center space-x-3">
          <div className="p-2 rounded-lg bg-emerald-500/20 text-emerald-400">
            <CheckCircle2 className="w-4 h-4" />
          </div>
          <div>
            <span className="font-semibold text-white block">
              Active JWT Session Established
            </span>
            <span className="text-slate-400">
              User identity confirmed via Spring Security & PostgreSQL. Primary wallet automatically allocated. Financial transactions connect in Phase 3.
            </span>
          </div>
        </div>
        <a
          href="http://localhost:8080/swagger-ui.html"
          target="_blank"
          rel="noopener noreferrer"
          className="inline-flex items-center space-x-1 px-3 py-1.5 rounded-lg bg-nexa-navy-950 border border-nexa-navy-700 text-nexa-teal-400 hover:text-nexa-teal-300 font-medium flex-shrink-0"
        >
          <span>OpenAPI Docs</span>
          <ExternalLink className="w-3.5 h-3.5" />
        </a>
      </div>

      {/* Metric Cards Grid */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
        {/* Main Wallet Balance Card */}
        <div className="p-6 rounded-2xl bg-gradient-to-br from-nexa-navy-900 via-nexa-navy-900 to-nexa-navy-850 border border-nexa-navy-800 shadow-xl space-y-4">
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold uppercase tracking-wider text-slate-400">
              Primary Wallet Balance
            </span>
            <div className="p-2 rounded-xl bg-nexa-blue-600/20 text-nexa-blue-400">
              <Wallet className="w-5 h-5" />
            </div>
          </div>

          <div>
            <div className="flex items-baseline space-x-2">
              <span className="text-3xl sm:text-4xl font-extrabold text-white tracking-tight">
                ${balance}
              </span>
              <span className="text-xs font-bold text-nexa-teal-400 font-mono">{user?.currency || 'USD'}</span>
            </div>
            <div className="flex items-center space-x-2 mt-2 text-xs text-emerald-400">
              <TrendingUp className="w-3.5 h-3.5" />
              <span>Available for settlement</span>
            </div>
          </div>

          <div className="pt-3 border-t border-nexa-navy-800/80 flex items-center justify-between text-xs text-slate-400">
            <div className="flex items-center space-x-1.5">
              <span className="font-mono text-slate-300">{walletNumber}</span>
              <button
                onClick={copyWallet}
                className="p-1 rounded hover:bg-nexa-navy-800 text-slate-400 hover:text-slate-200 transition-colors"
                title="Copy Wallet Number"
              >
                <Copy className="w-3.5 h-3.5" />
              </button>
            </div>
            <span className="text-[11px] text-nexa-teal-400 font-semibold">{copied ? 'Copied!' : 'Active'}</span>
          </div>
        </div>

        {/* Double-Entry Ledger State */}
        <div className="p-6 rounded-2xl bg-nexa-navy-900 border border-nexa-navy-800 shadow-xl space-y-4">
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold uppercase tracking-wider text-slate-400">
              Account Status & Role
            </span>
            <div className="p-2 rounded-xl bg-nexa-teal-600/20 text-nexa-teal-400">
              <Layers className="w-5 h-5" />
            </div>
          </div>

          <div>
            <div className="flex items-baseline space-x-2">
              <span className="text-3xl font-extrabold text-white tracking-tight">
                {user?.role || 'ROLE_CUSTOMER'}
              </span>
            </div>
            <p className="text-xs text-slate-400 mt-2">
              Assigned chart-of-accounts: <code>USER_WALLET</code>
            </p>
          </div>

          <div className="pt-3 border-t border-nexa-navy-800 flex items-center justify-between text-xs text-slate-400">
            <span>Ledger Invariant</span>
            <span className="text-emerald-400 font-mono font-medium flex items-center space-x-1">
              <CheckCircle2 className="w-3.5 h-3.5" />
              <span>VERIFIED</span>
            </span>
          </div>
        </div>

        {/* Concurrency & Idempotency Safeguard */}
        <div className="p-6 rounded-2xl bg-nexa-navy-900 border border-nexa-navy-800 shadow-xl space-y-4">
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold uppercase tracking-wider text-slate-400">
              Session Security
            </span>
            <div className="p-2 rounded-xl bg-purple-600/20 text-purple-400">
              <ShieldCheck className="w-5 h-5" />
            </div>
          </div>

          <div>
            <div className="flex items-baseline space-x-2">
              <span className="text-3xl font-extrabold text-white tracking-tight">
                Stateless
              </span>
              <span className="text-xs font-bold text-purple-400 font-mono">JWT 15m</span>
            </div>
            <p className="text-xs text-slate-400 mt-2">
              HMAC-SHA256 signature verified on each protected API request
            </p>
          </div>

          <div className="pt-3 border-t border-nexa-navy-800 flex items-center justify-between text-xs text-slate-400">
            <span>In-Memory Token</span>
            <span className="text-nexa-teal-400 font-mono font-medium">XSS-Resistant</span>
          </div>
        </div>
      </div>

      {/* Analytics Chart Section */}
      <div className="p-6 rounded-2xl bg-nexa-navy-900 border border-nexa-navy-800 shadow-xl space-y-4">
        <div className="flex items-center justify-between">
          <div>
            <h2 className="text-base font-bold text-white">Cash Flow Analytics Preview</h2>
            <p className="text-xs text-slate-400">Recharts visualization of weekly transaction velocity</p>
          </div>
          <div className="flex items-center space-x-4 text-xs">
            <div className="flex items-center space-x-1.5">
              <span className="w-3 h-3 rounded-full bg-nexa-teal-400" />
              <span className="text-slate-300">Inflow</span>
            </div>
            <div className="flex items-center space-x-1.5">
              <span className="w-3 h-3 rounded-full bg-nexa-blue-500" />
              <span className="text-slate-300">Outflow</span>
            </div>
          </div>
        </div>

        <div className="h-64 w-full pt-4">
          <ResponsiveContainer width="100%" height="100%">
            <AreaChart data={mockAnalyticsData}>
              <defs>
                <linearGradient id="colorIncome" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%" stopColor="#14B8A6" stopOpacity={0.4} />
                  <stop offset="95%" stopColor="#14B8A6" stopOpacity={0} />
                </linearGradient>
                <linearGradient id="colorSpending" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%" stopColor="#3B82F6" stopOpacity={0.4} />
                  <stop offset="95%" stopColor="#3B82F6" stopOpacity={0} />
                </linearGradient>
              </defs>
              <XAxis dataKey="day" stroke="#64748B" fontSize={12} tickLine={false} />
              <YAxis stroke="#64748B" fontSize={12} tickLine={false} tickFormatter={(val) => `$${val}`} />
              <Tooltip
                contentStyle={{
                  backgroundColor: '#0A192F',
                  borderColor: '#233554',
                  borderRadius: '0.75rem',
                  color: '#F8FAFC',
                }}
              />
              <Area
                type="monotone"
                dataKey="income"
                stroke="#14B8A6"
                strokeWidth={2}
                fillOpacity={1}
                fill="url(#colorIncome)"
              />
              <Area
                type="monotone"
                dataKey="spending"
                stroke="#3B82F6"
                strokeWidth={2}
                fillOpacity={1}
                fill="url(#colorSpending)"
              />
            </AreaChart>
          </ResponsiveContainer>
        </div>
      </div>

      {/* Transfer Preview Modal */}
      {showTransferPreviewModal && (
        <div className="fixed inset-0 z-50 bg-black/70 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="w-full max-w-md bg-nexa-navy-900 border border-nexa-navy-800 rounded-2xl p-6 shadow-2xl space-y-5 animate-scaleUp">
            <div className="flex items-center justify-between">
              <h3 className="text-lg font-bold text-white">Transfer Preview</h3>
              <button
                onClick={() => setShowTransferPreviewModal(false)}
                className="text-slate-400 hover:text-white text-sm"
              >
                &times;
              </button>
            </div>

            <div className="p-3.5 rounded-xl bg-amber-500/10 border border-amber-500/30 text-xs text-amber-200/90 flex items-start space-x-2.5">
              <AlertCircle className="w-4 h-4 text-amber-400 flex-shrink-0 mt-0.5" />
              <div>
                <p className="font-semibold text-amber-300">Phase 3 Financial Core</p>
                <p className="text-[11px] text-amber-200/80 mt-0.5">
                  Money transfers and simulated deposits will be executed against PostgreSQL with pessimistic lock ordering in Phase 3.
                </p>
              </div>
            </div>

            <div className="flex gap-3 pt-2">
              <button
                type="button"
                onClick={() => setShowTransferPreviewModal(false)}
                className="w-full py-2.5 rounded-xl bg-nexa-navy-800 text-slate-300 hover:text-white font-semibold text-xs"
              >
                Close Preview
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
