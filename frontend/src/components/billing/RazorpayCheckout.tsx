import { useEffect, useCallback, useRef } from 'react';

// Declare Razorpay global type
declare global {
  interface Window {
    Razorpay: any;
  }
}

interface RazorpayCheckoutProps {
  subscriptionId: string;
  razorpayKeyId: string;
  onSuccess: (response: {
    razorpay_payment_id: string;
    razorpay_subscription_id: string;
    razorpay_signature: string;
  }) => void;
  onFailure: () => void;
  userName?: string;
  userEmail?: string;
  planName?: string;
  amountPaise?: number;
}

export function RazorpayCheckout({
  subscriptionId,
  razorpayKeyId,
  onSuccess,
  onFailure,
  userName,
  userEmail,
  planName,
  amountPaise,
}: RazorpayCheckoutProps) {

  const openedRef = useRef(false);

  // Load Razorpay script dynamically
  useEffect(() => {
    if (document.getElementById('razorpay-script')) return;
    const script = document.createElement('script');
    script.id = 'razorpay-script';
    script.src = 'https://checkout.razorpay.com/v1/checkout.js';
    script.async = true;
    document.body.appendChild(script);
  }, []);

  // Auto-open checkout once when component mounts
  useEffect(() => {
    if (openedRef.current) return;

    const doOpen = () => {
      if (openedRef.current) return;
      openedRef.current = true;

      if (!window.Razorpay) {
        console.error('Razorpay script not loaded');
        onFailure();
        return;
      }

      const gstNote = amountPaise
        ? `${planName || 'Subscription'} — ₹${(amountPaise / 100).toLocaleString('en-IN')} + 18% GST`
        : (planName || 'Subscription');

      const options = {
        key: razorpayKeyId,
        subscription_id: subscriptionId,
        name: 'ExecuteSpec',
        description: gstNote,
        prefill: {
          name: userName || '',
          email: userEmail || '',
        },
        handler: onSuccess,
        modal: {
          ondismiss: onFailure,
          confirm_close: true,
        },
        theme: {
          color: '#6366f1',
        },
      };

      const rzp = new window.Razorpay(options);
      rzp.open();
    };

    const timer = setTimeout(() => {
      if (window.Razorpay) {
        doOpen();
      } else {
        const scriptEl = document.getElementById('razorpay-script');
        scriptEl?.addEventListener('load', doOpen);
      }
    }, 500);

    return () => clearTimeout(timer);
  }, [subscriptionId, razorpayKeyId, onSuccess, onFailure, userName, userEmail]);

  return null; // Checkout is a modal overlay, no visible component
}
