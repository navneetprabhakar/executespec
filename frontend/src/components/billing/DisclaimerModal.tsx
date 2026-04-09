import { useState, useEffect } from 'react';

interface DisclaimerModalProps {
  isOpen: boolean;
  onAccept: () => void;
  onCancel: () => void;
}

export function DisclaimerModal({ isOpen, onAccept, onCancel }: DisclaimerModalProps) {
  const [accepted, setAccepted] = useState(false);
  const [processing, setProcessing] = useState(false);

  // Reset checkbox state each time the modal opens
  useEffect(() => {
    if (isOpen) { setAccepted(false); setProcessing(false); }
  }, [isOpen]);

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-2 md:p-4 bg-[var(--gray-200)]">
      <div className="bg-[var(--gray-100)] border border-[var(--border-default)] rounded-md max-w-lg w-full mx-4 max-h-[80vh] flex flex-col">
        <div className="p-6 border-b">
          <h2 className="text-lg font-semibold text-primary">Terms of Use & Disclaimer</h2>
        </div>

        <div className="p-6 overflow-y-auto text-sm text-secondary space-y-3 flex-1">
          <p><strong>1. "As-Is" Provision:</strong> ExecuteSpec products are provided "as-is" and "as-available" with no warranties of any kind.</p>
          <p><strong>2. No Obligation:</strong> ExecuteSpec shall not be obligated to provide uninterrupted service, guaranteed uptime, or any SLA unless explicitly agreed in a separate Enterprise contract.</p>
          <p><strong>3. AI-Generated Output:</strong> All outputs are AI-generated and must be reviewed, validated, and tested before production use. ExecuteSpec assumes no liability for damages arising from generated outputs.</p>
          <p><strong>4. Refund Policy:</strong> Cancellation takes effect at the end of the current billing period with no refund. When upgrading to a higher plan, a prorated refund is automatically issued for unused runs on your current plan (refund = unused runs / total run limit x amount paid). Refunds are credited to the original payment method within 5–7 business days. A credit note is emailed for your records.</p>
          <p><strong>5. Top-Up Runs:</strong> Run top-up purchases are non-refundable. Top-up runs do not expire and remain available until used.</p>
          <p><strong>6. Usage Limits:</strong> Subject to fair-use limits per plan.</p>
          <p><strong>7. Pricing Changes:</strong> Pricing may change with 30 days' notice. Founder Pricing subscribers are exempt.</p>
          <p><strong>8. BYOK Disclaimer:</strong> When using Bring Your Own Keys, users are responsible for their own API key costs and compliance with LLM provider terms.</p>
          <p><strong>9. Data Handling:</strong> Users retain ownership of all inputs and outputs.</p>
          <p><strong>10. Governing Law:</strong> Laws of India, exclusive jurisdiction in courts of Bengaluru, Karnataka.</p>
        </div>

        <div className="p-6 border-t space-y-4">
          <label className="flex items-start gap-3 cursor-pointer">
            <input
              type="checkbox"
              checked={accepted}
              onChange={(e) => setAccepted(e.target.checked)}
              className="mt-0.5 h-4 w-4 rounded border-[var(--border-default)] text-accent-blue focus:ring-accent-blue"
            />
            <span className="text-sm text-secondary">
              I have read and agree to the Terms of Use & Disclaimer
            </span>
          </label>

          <div className="flex gap-3 justify-end">
            <button
              onClick={onCancel}
              className="px-4 py-2 text-sm text-secondary bg-[var(--gray-200)] rounded-sm hover:bg-[var(--gray-300)]"
            >
              Cancel
            </button>
            <button
              onClick={() => { setProcessing(true); onAccept(); }}
              disabled={!accepted || processing}
              className="px-4 py-2 text-sm text-primary bg-accent-blue rounded-sm hover:bg-accent-blue-hover disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2"
            >
              {processing && (
                <span className="h-4 w-4 rounded-full border-2 border-white/30 border-t-white animate-spin" />
              )}
              {processing ? 'Preparing checkout…' : 'Continue to Payment'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
