import React, { useCallback, useEffect, useState } from 'react';
import { History, Loader2, AlertCircle, Inbox, ChevronLeft, ChevronRight, ArrowDownLeft, ArrowUpRight } from 'lucide-react';
import { fetchTransactions, extractApiErrorMessage, type TransactionData } from '../../services/api';
import { formatCurrency, formatDateTime } from '../../utils/money';

interface TransactionHistoryProps {
  walletNumber: string | undefined;
  /** Bumped by the parent after a deposit/transfer settles, to trigger a refetch of page 0. */
  refreshToken: number;
}

const PAGE_SIZE = 8;

const STATUS_STYLES: Record<string, string> = {
  SETTLED: 'bg-emerald-500/10 text-emerald-400 border-emerald-500/30',
  PENDING: 'bg-amber-500/10 text-amber-400 border-amber-500/30',
  FAILED: 'bg-red-500/10 text-red-400 border-red-500/30',
  REVERSED: 'bg-slate-500/10 text-slate-300 border-slate-500/30',
};

export const TransactionHistory: React.FC<TransactionHistoryProps> = ({ walletNumber, refreshToken }) => {
  const [page, setPage] = useState(0);
  const [data, setData] = useState<{ content: TransactionData[]; totalPages: number; first: boolean; last: boolean } | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async (targetPage: number) => {
    setLoading(true);
    setError(null);
    try {
      const result = await fetchTransactions(targetPage, PAGE_SIZE);
      setData(result);
      setPage(result.number);
    } catch (err) {
      setError(extractApiErrorMessage(err, 'Transaction history could not be loaded.'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [refreshToken]);

  const isCredit = (tx: TransactionData) => tx.targetWalletNumber === walletNumber && tx.sourceWalletNumber !== walletNumber;

  return (
    <div className="p-6 rounded-2xl bg-nexa-navy-900 border border-nexa-navy-800 shadow-xl space-y-4">
      <div className="flex items-center justify-between">
        <div className="flex items-center space-x-2">
          <div className="p-2 rounded-xl bg-nexa-blue-600/20 text-nexa-blue-400">
            <History className="w-5 h-5" />
          </div>
          <div>
            <h2 className="text-base font-bold text-white">Recent Activity</h2>
            <p className="text-xs text-slate-400">Settled deposits and transfers from your wallet ledger</p>
          </div>
        </div>
      </div>

      {loading && (
        <div className="flex items-center justify-center space-x-2 text-sm text-slate-400 py-10">
          <Loader2 className="w-4 h-4 animate-spin" />
          <span>Loading transactions...</span>
        </div>
      )}

      {!loading && error && (
        <div className="p-3.5 rounded-xl bg-red-500/10 border border-red-500/30 text-xs text-red-300 flex items-start space-x-2.5">
          <AlertCircle className="w-4 h-4 text-red-400 flex-shrink-0 mt-0.5" />
          <div className="flex-1">
            <p>{error}</p>
            <button onClick={() => load(page)} className="mt-2 text-red-200 underline underline-offset-2 hover:text-white">
              Retry
            </button>
          </div>
        </div>
      )}

      {!loading && !error && data && data.content.length === 0 && (
        <div className="flex flex-col items-center justify-center space-y-2 text-sm text-slate-400 py-10">
          <Inbox className="w-6 h-6 text-slate-500" />
          <span>No transactions yet. Simulate a deposit to get started.</span>
        </div>
      )}

      {!loading && !error && data && data.content.length > 0 && (
        <>
          {/* Desktop table */}
          <div className="hidden sm:block overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-[11px] uppercase tracking-wider text-slate-400 border-b border-nexa-navy-800">
                  <th className="pb-2 pr-4 font-semibold">Type</th>
                  <th className="pb-2 pr-4 font-semibold">Reference</th>
                  <th className="pb-2 pr-4 font-semibold">Amount</th>
                  <th className="pb-2 pr-4 font-semibold">Status</th>
                  <th className="pb-2 font-semibold">Date</th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((tx) => {
                  const credit = isCredit(tx);
                  return (
                    <tr key={tx.transactionId} className="border-b border-nexa-navy-800/60 last:border-0">
                      <td className="py-3 pr-4">
                        <div className="flex items-center space-x-2">
                          {credit ? (
                            <ArrowDownLeft className="w-4 h-4 text-emerald-400" />
                          ) : (
                            <ArrowUpRight className="w-4 h-4 text-nexa-blue-400" />
                          )}
                          <span className="text-slate-200 font-medium">{tx.type.replace(/_/g, ' ')}</span>
                        </div>
                      </td>
                      <td className="py-3 pr-4 font-mono text-xs text-slate-400">{tx.referenceCode}</td>
                      <td className={`py-3 pr-4 font-semibold ${credit ? 'text-emerald-400' : 'text-slate-200'}`}>
                        {credit ? '+' : '-'}{formatCurrency(tx.amount, tx.currency)}
                      </td>
                      <td className="py-3 pr-4">
                        <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-semibold border ${STATUS_STYLES[tx.status] || 'bg-slate-500/10 text-slate-300 border-slate-500/30'}`}>
                          {tx.status}
                        </span>
                      </td>
                      <td className="py-3 text-xs text-slate-400">{formatDateTime(tx.createdAt)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>

          {/* Mobile cards */}
          <div className="sm:hidden space-y-2">
            {data.content.map((tx) => {
              const credit = isCredit(tx);
              return (
                <div key={tx.transactionId} className="p-3 rounded-xl bg-nexa-navy-950/60 border border-nexa-navy-800/80 space-y-1.5">
                  <div className="flex items-center justify-between">
                    <div className="flex items-center space-x-2">
                      {credit ? <ArrowDownLeft className="w-4 h-4 text-emerald-400" /> : <ArrowUpRight className="w-4 h-4 text-nexa-blue-400" />}
                      <span className="text-sm font-medium text-slate-200">{tx.type.replace(/_/g, ' ')}</span>
                    </div>
                    <span className={`font-semibold text-sm ${credit ? 'text-emerald-400' : 'text-slate-200'}`}>
                      {credit ? '+' : '-'}{formatCurrency(tx.amount, tx.currency)}
                    </span>
                  </div>
                  <div className="flex items-center justify-between text-[11px] text-slate-400">
                    <span className="font-mono">{tx.referenceCode}</span>
                    <span className={`inline-flex items-center px-2 py-0.5 rounded-full font-semibold border ${STATUS_STYLES[tx.status] || 'bg-slate-500/10 text-slate-300 border-slate-500/30'}`}>
                      {tx.status}
                    </span>
                  </div>
                  <p className="text-[11px] text-slate-500">{formatDateTime(tx.createdAt)}</p>
                </div>
              );
            })}
          </div>

          {/* Pagination */}
          <div className="flex items-center justify-between pt-2 border-t border-nexa-navy-800/80">
            <span className="text-xs text-slate-400">Page {page + 1} of {Math.max(data.totalPages, 1)}</span>
            <div className="flex items-center space-x-2">
              <button
                onClick={() => load(page - 1)}
                disabled={data.first || loading}
                aria-label="Previous page"
                className="p-1.5 rounded-lg bg-nexa-navy-800 border border-nexa-navy-700 text-slate-300 hover:text-white disabled:opacity-40"
              >
                <ChevronLeft className="w-4 h-4" />
              </button>
              <button
                onClick={() => load(page + 1)}
                disabled={data.last || loading}
                aria-label="Next page"
                className="p-1.5 rounded-lg bg-nexa-navy-800 border border-nexa-navy-700 text-slate-300 hover:text-white disabled:opacity-40"
              >
                <ChevronRight className="w-4 h-4" />
              </button>
            </div>
          </div>
        </>
      )}
    </div>
  );
};
