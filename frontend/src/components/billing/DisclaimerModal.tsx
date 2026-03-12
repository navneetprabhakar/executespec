import { useState } from 'react';

interface DisclaimerModalProps {
  isOpen: boolean;
  onAccept: () => void;
  onCancel: () => void;
}

export function DisclaimerModal({ isOpen, onAccept, onCancel }: DisclaimerModalProps) {
  const [accepted, setAccepted] = useState(false);

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
      <div className="bg-white rounded-lg shadow-xl max-w-lg w-full mx-4 max-h-[80vh] flex flex-col">
        <div className="p-6 border-b">
          <h2 className="text-lg font-semibold text-gray-900">Terms of Use & Disclaimer</h2>
        </div>

        <div className="p-6 overflow-y-auto text-sm text-gray-700 space-y-3 flex-1">
          <p><strong>1. "As-Is" Provision:</strong> ExecuteSpec products are provided "as-is" and "as-available" with no warranties of any kind.</p>
          <p><strong>2. No Obligation:</strong> ExecuteSpec shall not be obligated to provide uninterrupted service, guaranteed uptime, or any SLA unless explicitly agreed in a separate Enterprise contract.</p>
          <p><strong>3. AI-Generated Output:</strong> All outputs are AI-generated and must be reviewed, validated, and tested before production use. ExecuteSpec assumes no liability for damages arising from generated outputs.</p>
          <p><strong>4. No Refunds:</strong> All payments are non-refundable. Cancellation takes effect at the end of the current billing period.</p>
          <p><strong>5. Usage Limits:</strong> Subject to fair-use limits per plan.</p>
          <p><strong>6. Pricing Changes:</strong> Pricing may change with 30 days' notice. Founder Pricing subscribers are exempt.</p>
          <p><strong>7. BYOK Disclaimer:</strong> When using Bring Your Own Keys, users are responsible for their own API key costs and compliance with LLM provider terms.</p>
          <p><strong>8. Data Handling:</strong> Users retain ownership of all inputs and outputs.</p>
          <p><strong>9. Governing Law:</strong> Laws of India, exclusive jurisdiction in courts of Bengaluru, Karnataka.</p>
        </div>

        <div className="p-6 border-t space-y-4">
          <label className="flex items-start gap-3 cursor-pointer">
            <input
              type="checkbox"
              checked={accepted}
              onChange={(e) => setAccepted(e.target.checked)}
              className="mt-0.5 h-4 w-4 rounded border-gray-300 text-indigo-600 focus:ring-indigo-500"
            />
            <span className="text-sm text-gray-700">
              I have read and agree to the Terms of Use & Disclaimer
            </span>
          </label>

          <div className="flex gap-3 justify-end">
            <button
              onClick={onCancel}
              className="px-4 py-2 text-sm text-gray-700 bg-gray-100 rounded-lg hover:bg-gray-200"
            >
              Cancel
            </button>
            <button
              onClick={onAccept}
              disabled={!accepted}
              className="px-4 py-2 text-sm text-white bg-indigo-600 rounded-lg hover:bg-indigo-700 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              Continue to Payment
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
