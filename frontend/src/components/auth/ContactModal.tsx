import {useState} from 'react';
import {errMsg} from '@/lib/utils';
import {submitContactForm} from '@/lib/api';
import {useUiStore} from '@/stores/useUiStore';
import ResponsiveModal from '@/components/shared/ResponsiveModal';
import Button from '@/components/shared/Button';

interface ContactModalProps {
  onClose: () => void;
}

export default function ContactModal({onClose}: ContactModalProps) {
  const [subject, setSubject] = useState('');
  const [body, setBody] = useState('');
  const [loading, setLoading] = useState(false);
  const {addToast} = useUiStore();

  const handleSubmit = async () => {
    if (!subject.trim() || !body.trim()) return;
    setLoading(true);
    try {
      await submitContactForm(subject, body);
      addToast({type: 'success', message: 'Feedback submitted successfully'});
      onClose();
    } catch (e) {
      addToast({type: 'error', message: errMsg(e) || 'Failed to submit feedback'});
      setLoading(false);
    }
  };

  return (
    <ResponsiveModal open={true} onClose={onClose} title='Contact Us' maxWidth='max-w-md'>
      <div className='space-y-3'>
        <div>
          <label className='block text-sm text-secondary mb-1.5'>Subject</label>
          <input
            value={subject}
            onChange={(e) => setSubject(e.target.value)}
            placeholder='What is this about?'
            maxLength={200}
            className='w-full bg-[var(--gray-100)] border border-[var(--border-default)] rounded-sm px-3 py-2 text-sm text-primary focus:outline-none focus:border-accent-blue/40'
          />
        </div>
        <div>
          <label className='block text-sm text-secondary mb-1.5'>Feedback</label>
          <textarea
            value={body}
            onChange={(e) => setBody(e.target.value)}
            placeholder="Tell us what's on your mind..."
            maxLength={5000}
            rows={6}
            className='w-full bg-[var(--gray-100)] border border-[var(--border-default)] rounded-sm px-3 py-2 text-sm text-primary focus:outline-none focus:border-accent-blue/40 resize-none'
          />
          <p className='mt-1 text-xs text-muted text-right'>{body.length}/5000</p>
        </div>
      </div>

      <Button
        onClick={handleSubmit}
        variant='primary'
        size='lg'
        fullWidth
        loading={loading}
        disabled={!subject.trim() || !body.trim() || loading}
        className='mt-4'
      >
        {loading ? 'Submitting...' : 'Submit Feedback'}
      </Button>
    </ResponsiveModal>
  );
}
