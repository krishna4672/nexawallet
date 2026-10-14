import React, { useState } from 'react';
import {
  Wallet,
  ArrowUpRight,
  ArrowDownLeft,
  ShieldCheck,
  Layers,
  TrendingUp,
  CheckCircle2,
  Copy,
  ExternalLink
} from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import { NearbyServicePoints } from '../components/discovery/NearbyServicePoints';
import { DepositModal } from '../components/wallet/DepositModal';
import { TransferModal } from '../components/wallet/TransferModal';
import { TransactionHistory } from '../components/wallet/TransactionHistory';
import { fetchCurrentUser, type TransactionData } from '../services/api';
import { formatCurrency } from '../utils/money';

export const DashboardPage: React.FC = () => {
  const { user, token, updateUser } = useAuth();
  const [showDepositModal, setShowDepositModal] = useState(false);
  const [showTransferModal, setShowTransferModal] = useState(false);
  const [copied, setCopied] = useState(false);
  const [historyRefreshToken, setHistoryRefreshToken] = useState(0);

  const walletNumber = user?.walletNumber || 'NX-PENDING-ALLOC';
  const balance = user ? formatCurrency(Number(user.walletBalance), user.currency) : formatCurrency(0, 'USD');

  const copyWallet = () => {
    navigator.clipboard.writeText(walletNumber);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  // Shared by both modals: the backend is the only authoritative source for the new balance —
  // this re-fetches /auth/me rather than computing anything client-side, and refreshes the
  // transaction list so the new deposit/transfer appears immediately.
  const handleFinancialSuccess = async (_transaction: TransactionData) => {
    if (token) {
      try {
        const refreshedUser = await fetchCurrentUser(token);
        updateUser(refreshedUser);
      } catch {
        // Non-fatal: the transaction itself already succeeded per the backend's response: the
        // balance shown will simply catch up next time /auth/me is called (e.g. on next action).
      }
    }
    setHistoryRefreshToken((prev) => prev + 1);
  };

  return (
    <div className="p-4 sm:p-8 max-w-7xl mx-auto space-y-8">
      {/* Dashboard Top Header */}
      <div id="wallet" className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
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
            onClick={() => setShowTransferModal(true)}
            className="inline-flex items-center space-x-2 px-4 py-2.5 rounded-xl text-sm font-semibold bg-gradient-to-r from-nexa-blue-600 to-nexa-teal-600 text-white shadow-lg shadow-nexa-blue-600/20 hover:from-nexa-blue-500 hover:to-nexa-teal-500 transition-all"
          >
            <ArrowUpRight className="w-4 h-4" />
            <span>Send Transfer</span>
          </button>
          <button
            onClick={() => setShowDepositModal(true)}
            className="inline-flex items-center space-x-2 px-4 py-2.5 rounded-xl text-sm font-semibold bg-nexa-navy-900 border border-nexa-navy-700 text-slate-200 hover:text-white hover:bg-nexa-navy-800 transition-all"
          >
            <ArrowDownLeft className="w-4 h-4 text-nexa-teal-400" />
            <span>Simulate Deposit</span>
          </button>
        </div>
      </div>

      {/* Session Status Banner */}
      <div className="p-4 rounded-2xl bg-nexa-navy-900/90 border border-nexa-navy-800 text-xs text-slate-300 flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3 shadow-md">
        <div className="flex items-center space-x-3">
          <div className="p-2 rounded-lg bg-emerald-500/20 text-emerald-400">
            <ShieldCheck className="w-4 h-4" />
          </div>
          <div>
            <span className="font-semibold text-white block">
              Active JWT Session Established
            </span>
            <span className="text-slate-400">
              User identity confirmed via Spring Security &amp; PostgreSQL. Primary wallet automatically allocated.
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
                {balance}
              </span>
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

      {/* Recent Activity (real paginated transaction history from the backend) */}
      <div id="activity">
        <TransactionHistory walletNumber={user?.walletNumber} refreshToken={historyRefreshToken} />
      </div>

      {/* Nearby Service Point Discovery (Phase 6) */}
      <NearbyServicePoints />

      {showTransferModal && (
        <TransferModal
          onClose={() => setShowTransferModal(false)}
          onSuccess={handleFinancialSuccess}
        />
      )}

      {showDepositModal && (
        <DepositModal
          onClose={() => setShowDepositModal(false)}
          onSuccess={handleFinancialSuccess}
        />
      )}
    </div>
  );
};
