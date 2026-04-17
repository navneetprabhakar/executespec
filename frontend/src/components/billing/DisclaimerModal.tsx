import { useState, useEffect } from 'react';
import ResponsiveModal from '@/components/shared/ResponsiveModal';
import Button from '@/components/shared/Button';
import Checkbox from '@/components/shared/Checkbox';

interface DisclaimerModalProps {
  isOpen: boolean;
  onAccept: () => void;
  onCancel: () => void;
}

export function DisclaimerModal({ isOpen, onAccept, onCancel }: DisclaimerModalProps) {
  const [accepted, setAccepted] = useState(false);
  const [processing, setProcessing] = useState(false);

  useEffect(() => {
    if (isOpen) { setAccepted(false); setProcessing(false); }
  }, [isOpen]);

  return (
    <ResponsiveModal
      open={isOpen}
      onClose={onCancel}
      title="Terms of Use & Disclaimer"
      maxWidth="max-w-lg"
    >
      <div className="text-sm text-secondary space-y-3">
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

      <div className="mt-4 pt-4 border-t border-[var(--border-default)] space-y-4">
        <Checkbox
          id="disclaimer-accept"
          checked={accepted}
          onChange={(e) => setAccepted(e.target.checked)}
          label="I have read and agree to the Terms of Use & Disclaimer"
        />

        <div className="flex gap-3 justify-end">
          <Button variant="secondary" size="md" onClick={onCancel}>
            Cancel
          </Button>
          <Button
            variant="primary"
            size="md"
            onClick={() => { setProcessing(true); onAccept(); }}
            disabled={!accepted || processing}
            loading={processing}
          >
            {processing ? 'Preparing checkout…' : 'Continue to Payment'}
          </Button>
        </div>
      </div>
    </ResponsiveModal>
  );
}
