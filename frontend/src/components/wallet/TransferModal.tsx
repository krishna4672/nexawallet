import React, { useId, useRef, useState } from 'react';
import { ArrowUpRight, Loader2, AlertCircle, CheckCircle2, HelpCircle } from 'lucide-react';
import axios from 'axios';
import { transferFunds, extractApiErrorMessage, type TransactionData } from '../../services/api';
import { parsePositiveAmount } from '../../utils/money';

interface TransferModalProps {
  onClose: () => void;
  onSuccess: (transaction: TransactionData) => void;
}

type Outcome = 'success' | 'failed' | 'uncertain';

/**
 * Peer-to-peer transfer form. Idempotency-Key handling (see README Phase 3 for the backend side):
 *
 * - A fresh key (crypto.randomUUID()) is generated the first time the user submits a given
 *   recipient+amount+description combination.
 * - If that same combination is resubmitted (the user clicks "Retry" after a failure OR an
 *   uncertain network outcome), the SAME key is reused — this is what lets the backend's
 *   idempotency check safely replay/complete the original attempt instead of double-charging.
 * - The moment the user changes the recipient, amount, or description, that's a genuinely new
 *   logical transfer, so a new key is generated.
 * - After a confirmed SETTLED success, the key is cleared — a subsequent transfer (even to the
 *   same recipient for the same amount) is a new logical operation and gets its own fresh key.
 * - This component never retries automatically, and never swaps in a different key on retry.
 */
export const TransferModal: React.FC<TransferModalProps> = ({ onClose, onSuccess }) => {
  const [targetWalletNumber, setTargetWalletNumber] = useState('');
  const [amount, setAmount] = useState('');
  const [description, setDescription] = useState('');
  const [fieldError, setFieldError] = useState<string | null>(null);
  const [apiError, setApiError] = useState<string | null>(null);
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [settled, setSettled] = useState<TransactionData | null>(null);

  const recipientInputId = useId();
  const amountInputId = useId();
  const descriptionInputId = useId();

  // Tracks which logical operation the current idempotency key belongs to, so editing any field
  // after a failed/uncertain attempt correctly starts a new key instead of reusing a stale one.
  const pendingKeyRef = useRef<string | null>(null);
  const pendingSignatureRef = useRef<string | null>(null);

  const currentSignature = () => `${targetWalletNumber.trim()}|${amount.trim()}|${description.trim()}`;

  const resolveIdempotencyKey = (): string => {
    const signature = currentSignature();
    if (pendingKeyRef.current && pendingSignatureRef.current === signature) {
      return pendingKeyRef.current; // retrying the same logical transfer
    }
    const freshKey = crypto.randomUUID();
    pendingKeyRef.current = freshKey;
    pendingSignatureRef.current = signature;
    return freshKey;
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setApiError(null);
    setFieldError(null);
    setOutcome(null);

    const trimmedTarget = targetWalletNumber.trim();
    if (!trimmedTarget) {
      setFieldError('Enter the recipient wallet number.');
      return;
    }
    const parsedAmount = parsePositiveAmount(amount);
    if (parsedAmount === null) {
      setFieldError('Enter a positive amount (up to 4 decimal places).');
      return;
    }

    const idempotencyKey = resolveIdempotencyKey();

    setIsSubmitting(true);
    try {
      const transaction = await transferFunds(
        { targetWalletNumber: trimmedTarget, amount: parsedAmount, description: description.trim() || undefined },
        idempotencyKey
      );
      setSettled(transaction);
      setOutcome('success');
      pendingKeyRef.current = null; // next transfer, even identical, is a new logical operation
      pendingSignatureRef.current = null;
      onSuccess(transaction);
    } catch (err) {
      // No response at all (timeout/network drop) means we genuinely don't know whether the
      // backend received and processed the request — that's an "uncertain" outcome, not a
      // confirmed failure, and retrying must reuse the same key rather than risk a double transfer.
      const isUncertain = axios.isAxiosError(err) && !err.response;
      setOutcome(isUncertain ? 'uncertain' : 'failed');
      setApiError(extractApiErrorMessage(err, 'The transfer could not be completed. Please try again.'));
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 bg-black/70 backdrop-blur-sm flex items-center justify-center p-4" role="dialog" aria-modal="true" aria-labelledby="transfer-modal-title">
      <div className="w-full max-w-md bg-nexa-navy-900 border border-nexa-navy-800 rounded-2xl p-6 shadow-2xl space-y-5">
        <div className="flex items-center justify-between">
          <h3 id="transfer-modal-title" className="text-lg font-bold text-white flex items-center space-x-2">
            <ArrowUpRight className="w-5 h-5 text-nexa-blue-400" />
            <span>Send Transfer</span>
          </h3>
          <button onClick={onClose} className="text-slate-400 hover:text-white text-sm" aria-label="Close">
            &times;
          </button>
        </div>

        {settled && outcome === 'success' ? (
          <div className="p-4 rounded-xl bg-emerald-500/10 border border-emerald-500/30 text-sm text-emerald-200 flex items-start space-x-2.5">
            <CheckCircle2 className="w-5 h-5 text-emerald-400 flex-shrink-0" />
            <div>
              <p className="font-semibold">Transfer settled</p>
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
              <label htmlFor={recipientInputId} className="text-xs font-semibold uppercase tracking-wider text-slate-300">
                Recipient Wallet Number
              </label>
              <input
                id={recipientInputId}
                type="text"
                value={targetWalletNumber}
                onChange={(e) => setTargetWalletNumber(e.target.value)}
                placeholder="NX-0000-0000-0000"
                disabled={isSubmitting}
                className="w-full px-3.5 py-2.5 bg-nexa-navy-950 border border-nexa-navy-700 rounded-xl text-sm text-white placeholder-slate-500 focus:outline-none focus:border-nexa-blue-400 focus:ring-1 focus:ring-nexa-blue-400 disabled:opacity-60 font-mono"
              />
            </div>

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
                placeholder="50.00"
                disabled={isSubmitting}
                className="w-full px-3.5 py-2.5 bg-nexa-navy-950 border border-nexa-navy-700 rounded-xl text-sm text-white placeholder-slate-500 focus:outline-none focus:border-nexa-blue-400 focus:ring-1 focus:ring-nexa-blue-400 disabled:opacity-60"
              />
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
                placeholder="e.g. Rent split"
                disabled={isSubmitting}
                maxLength={255}
                className="w-full px-3.5 py-2.5 bg-nexa-navy-950 border border-nexa-navy-700 rounded-xl text-sm text-white placeholder-slate-500 focus:outline-none focus:border-nexa-blue-400 focus:ring-1 focus:ring-nexa-blue-400 disabled:opacity-60"
              />
            </div>

            {fieldError && (
              <div className="p-3 rounded-xl bg-amber-500/10 border border-amber-500/30 text-xs text-amber-200 flex items-start space-x-2">
                <AlertCircle className="w-4 h-4 text-amber-400 flex-shrink-0 mt-0.5" />
                <span>{fieldError}</span>
              </div>
            )}

            {apiError && outcome === 'uncertain' && (
              <div className="p-3 rounded-xl bg-amber-500/10 border border-amber-500/30 text-xs text-amber-200 flex items-start space-x-2">
                <HelpCircle className="w-4 h-4 text-amber-400 flex-shrink-0 mt-0.5" />
                <div>
                  <p className="font-semibold">Outcome unknown</p>
                  <p className="mt-0.5 text-amber-200/80">
                    {apiError} We could not confirm whether this transfer went through. Click "Send Transfer" again —
                    it will safely retry the exact same transfer without double-charging.
                  </p>
                </div>
              </div>
            )}

            {apiError && outcome === 'failed' && (
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
                {isSubmitting ? <Loader2 className="w-4 h-4 animate-spin" /> : <ArrowUpRight className="w-4 h-4" />}
                <span>{isSubmitting ? 'Sending...' : outcome ? 'Retry Send Transfer' : 'Send Transfer'}</span>
              </button>
            </div>
          </form>
        )}
      </div>
    </div>
  );
};
