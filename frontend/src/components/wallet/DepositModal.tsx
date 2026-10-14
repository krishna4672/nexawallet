import React, { useId, useState } from 'react';
import { ArrowDownLeft, Loader2, AlertCircle, CheckCircle2, Info } from 'lucide-react';
import { depositFunds, extractApiErrorMessage, type TransactionData } from '../../services/api';
import { parsePositiveAmount } from '../../utils/money';

interface DepositModalProps {
  onClose: () => void;
  onSuccess: (transaction: TransactionData) => void;
}

/**
 * Simulated deposit — clearly labeled as such. No real bank or payment processor is connected;
 * POST /api/v1/wallet/deposit simply credits the wallet directly for demo purposes. Confirmation
 * is shown only after the backend responds with a SETTLED transaction, never optimistically.
 */
export const DepositModal: React.FC<DepositModalProps> = ({ onClose, onSuccess }) => {
  const [amount, setAmount] = useState('');
  const [description, setDescription] = useState('');
  const [fieldError, setFieldError] = useState<string | null>(null);
  const [apiError, setApiError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [settled, setSettled] = useState<TransactionData | null>(null);
  const amountInputId = useId();
  const descriptionInputId = useId();

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setApiError(null);
    setFieldError(null);

    const parsedAmount = parsePositiveAmount(amount);
    if (parsedAmount === null) {
      setFieldError('Enter a positive amount (up to 4 decimal places).');
      return;
    }

    setIsSubmitting(true);
    try {
      const transaction = await depositFunds({ amount: parsedAmount, description: description.trim() || undefined });
      setSettled(transaction);
      onSuccess(transaction);
    } catch (err) {
      setApiError(extractApiErrorMessage(err, 'The deposit could not be completed. Please try again.'));
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 bg-black/70 backdrop-blur-sm flex items-center justify-center p-4" role="dialog" aria-modal="true" aria-labelledby="deposit-modal-title">
      <div className="w-full max-w-md bg-nexa-navy-900 border border-nexa-navy-800 rounded-2xl p-6 shadow-2xl space-y-5">
        <div className="flex items-center justify-between">
          <h3 id="deposit-modal-title" className="text-lg font-bold text-white flex items-center space-x-2">
            <ArrowDownLeft className="w-5 h-5 text-nexa-teal-400" />
            <span>Simulate a Deposit</span>
          </h3>
          <button onClick={onClose} className="text-slate-400 hover:text-white text-sm" aria-label="Close">
            &times;
          </button>
        </div>

        <div className="p-3 rounded-xl bg-amber-500/10 border border-amber-500/30 text-[11px] text-amber-200/90 flex items-start space-x-2">
          <Info className="w-4 h-4 text-amber-400 flex-shrink-0 mt-0.5" />
          <span>This is a simulated deposit for demonstration purposes only — no real bank or payment processor is connected.</span>
        </div>

        {settled ? (
          <div className="p-4 rounded-xl bg-emerald-500/10 border border-emerald-500/30 text-sm text-emerald-200 flex items-start space-x-2.5">
            <CheckCircle2 className="w-5 h-5 text-emerald-400 flex-shrink-0" />
            <div>
              <p className="font-semibold">Deposit settled</p>
              <p className="text-xs text-emerald-200/80 mt-1">
                Reference {settled.referenceCode} — the backend confirmed this transaction as {settled.status}.
              </p>
              <button
                onClick={onClose}
                className="mt-3 px-3 py-1.5 rounded-lg bg-nexa-navy-950 border border-nexa-navy-700 text-xs font-semibold text-slate-200 hover:text-white"
              >
                Close
              </button>
            </div>
          </div>
        ) : (
          <form onSubmit={handleSubmit} className="space-y-4">
            <div className="space-y-1.5">
              <label htmlFor={amountInputId} className="text-xs font-semibold uppercase tracking-wider text-slate-300">
                Amount (USD)
              </label>
              <input
                id={amountInputId}
                type="text"
                inputMode="decimal"
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
                placeholder="100.00"
                disabled={isSubmitting}
                aria-invalid={Boolean(fieldError)}
                aria-describedby={fieldError ? `${amountInputId}-error` : undefined}
                className="w-full px-3.5 py-2.5 bg-nexa-navy-950 border border-nexa-navy-700 rounded-xl text-sm text-white placeholder-slate-500 focus:outline-none focus:border-nexa-teal-400 focus:ring-1 focus:ring-nexa-teal-400 disabled:opacity-60"
              />
              {fieldError && (
                <p id={`${amountInputId}-error`} className="text-xs text-red-400">{fieldError}</p>
              )}
            </div>

            <div className="space-y-1.5">
              <label htmlFor={descriptionInputId} className="text-xs font-semibold uppercase tracking-wider text-slate-300">
                Description (optional)
              </label>
              <input
                id={descriptionInputId}
                type="text"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder="e.g. Paycheck"
                disabled={isSubmitting}
                maxLength={255}
                className="w-full px-3.5 py-2.5 bg-nexa-navy-950 border border-nexa-navy-700 rounded-xl text-sm text-white placeholder-slate-500 focus:outline-none focus:border-nexa-teal-400 focus:ring-1 focus:ring-nexa-teal-400 disabled:opacity-60"
              />
            </div>

            {apiError && (
              <div className="p-3 rounded-xl bg-red-500/10 border border-red-500/30 text-xs text-red-300 flex items-start space-x-2">
                <AlertCircle className="w-4 h-4 text-red-400 flex-shrink-0 mt-0.5" />
                <span>{apiError}</span>
              </div>
            )}

            <div className="flex gap-3 pt-1">
              <button
                type="button"
                onClick={onClose}
                disabled={isSubmitting}
                className="flex-1 py-2.5 rounded-xl bg-nexa-navy-800 text-slate-300 hover:text-white font-semibold text-xs disabled:opacity-60"
              >
                Cancel
              </button>
              <button
                type="submit"
                disabled={isSubmitting}
                className="flex-1 inline-flex items-center justify-center space-x-2 py-2.5 rounded-xl font-semibold text-xs bg-gradient-to-r from-nexa-blue-600 to-nexa-teal-600 text-white disabled:opacity-70"
              >
                {isSubmitting ? <Loader2 className="w-4 h-4 animate-spin" /> : <ArrowDownLeft className="w-4 h-4" />}
                <span>{isSubmitting ? 'Processing...' : 'Deposit'}</span>
              </button>
            </div>
          </form>
        )}
      </div>
    </div>
  );
};
