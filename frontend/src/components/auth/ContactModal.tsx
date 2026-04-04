import { useState } from 'react';
import { motion } from 'framer-motion';
import { X, MessageSquare } from 'lucide-react';
import { submitContactForm } from '@/lib/api';
import { useUiStore } from '@/stores/useUiStore';
import { cn } from '@/lib/utils';

interface ContactModalProps {
  onClose: () => void;
}

export default function ContactModal({ onClose }: ContactModalProps) {
  const [subject, setSubject] = useState('');
  const [body, setBody] = useState('');
  const [loading, setLoading] = useState(false);
  const { addToast } = useUiStore();

  const handleSubmit = async () => {
    if (!subject.trim() || !body.trim()) return;
    setLoading(true);
    try {
      await submitContactForm(subject, body);
      addToast({ type: 'success', message: 'Feedback submitted successfully' });
      onClose();
    } catch (e: any) {
      addToast({ type: 'error', message: e.message || 'Failed to submit feedback' });
      setLoading(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-2 md:p-4"
      style={{ backdropFilter: 'blur(4px)', background: 'rgba(0,0,0,0.6)' }}
      onClick={onClose}
    >
      <motion.div
        initial={{ opacity: 0, scale: 0.96 }}
        animate={{ opacity: 1, scale: 1 }}
        onClick={(e) => e.stopPropagation()}
        className="w-full max-w-md rounded-sm bg-[var(--gray-200)] border border-[var(--border-default)] p-6 space-y-4 overflow-y-auto"
        style={{ maxHeight: '95dvh' }}
      >
        <div className="flex items-center justify-between">
          <h2 className="text-base font-semibold text-primary flex items-center gap-2">
            <MessageSquare className="h-4 w-4 text-accent-blue" /> Contact Us
          </h2>
          <button onClick={onClose} className="text-muted hover:text-secondary p-2 -m-1">
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="space-y-3">
          <div>
            <label className="block text-sm text-secondary mb-1.5">Subject</label>
            <input
              value={subject}
              onChange={(e) => setSubject(e.target.value)}
              placeholder="What is this about?"
              maxLength={200}
              className="w-full bg-[var(--gray-100)] border border-[var(--border-default)] rounded-sm px-3 py-2 text-sm text-primary focus:outline-none focus:border-accent-blue/40"
            />
          </div>
          <div>
            <label className="block text-sm text-secondary mb-1.5">Feedback</label>
            <textarea
              value={body}
              onChange={(e) => setBody(e.target.value)}
              placeholder="Tell us what's on your mind..."
              maxLength={5000}
              rows={6}
              className="w-full bg-[var(--gray-100)] border border-[var(--border-default)] rounded-sm px-3 py-2 text-sm text-primary focus:outline-none focus:border-accent-blue/40 resize-none"
            />
            <p className="mt-1 text-xs text-muted text-right">{body.length}/5000</p>
          </div>
        </div>

        <button
          onClick={handleSubmit}
          disabled={!subject.trim() || !body.trim() || loading}
          className={cn(
            'w-full py-2.5 rounded-sm font-medium text-sm text-primary transition-all',
            !subject.trim() || !body.trim() || loading
              ? 'bg-[var(--gray-400)] cursor-not-allowed opacity-50'
              : 'bg-accent-blue hover:brightness-110'
          )}
        >
          {loading ? 'Submitting...' : 'Submit Feedback'}
        </button>
      </motion.div>
    </div>
  );
}
