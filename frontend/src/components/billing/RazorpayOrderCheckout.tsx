import {useCallback, useEffect} from 'react';

// Razorpay global type is declared in RazorpayCheckout.tsx — re-importing it
// here would create duplicate-modifier errors. Both files share the same
// `window.Razorpay` runtime symbol from the Razorpay Checkout SDK.

interface RazorpayOrderCheckoutProps {
  orderId: string;
  razorpayKeyId: string;
  amountPaise: number;
  onSuccess: (response: {
    razorpay_order_id: string;
    razorpay_payment_id: string;
    razorpay_signature: string;
  }) => void;
  onFailure: () => void;
  userName?: string;
  userEmail?: string;
  description?: string;
}

/**
 * One-time Razorpay order checkout (for run top-up purchases).
 * Similar to RazorpayCheckout but uses order_id instead of subscription_id.
 */
export function RazorpayOrderCheckout({
  orderId,
  razorpayKeyId,
  amountPaise,
  onSuccess,
  onFailure,
  userName,
  userEmail,
  description = 'Run Top-Up',
}: RazorpayOrderCheckoutProps) {
  // Load Razorpay script dynamically
  useEffect(() => {
    if (document.getElementById('razorpay-script')) return;
    const script = document.createElement('script');
    script.id = 'razorpay-script';
    script.src = 'https://checkout.razorpay.com/v1/checkout.js';
    script.async = true;
    document.body.appendChild(script);
  }, []);

  const openCheckout = useCallback(() => {
    if (!window.Razorpay) {
      console.error('Razorpay script not loaded');
      onFailure();
      return;
    }

    const options = {
      key: razorpayKeyId,
      order_id: orderId,
      amount: amountPaise,
      currency: 'INR',
      name: 'ExecuteSpec',
      description,
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
  }, [orderId, razorpayKeyId, amountPaise, onSuccess, onFailure, userName, userEmail, description]);

  // Auto-open checkout when component mounts
  useEffect(() => {
    const timer = setTimeout(() => {
      if (window.Razorpay) {
        openCheckout();
      } else {
        const scriptEl = document.getElementById('razorpay-script');
        scriptEl?.addEventListener('load', openCheckout);
      }
    }, 500);

    return () => clearTimeout(timer);
  }, [openCheckout]);

  return null; // Checkout is a modal overlay, no visible component
}
