export type OrderView = {
  orderId: string;
  status: string;
};

export async function fetchOrder(orderId: string): Promise<OrderView> {
  const response = await fetch(`/api/orders/${encodeURIComponent(orderId)}`);
  if (!response.ok) throw new Error(`order lookup failed: ${response.status}`);
  return response.json() as Promise<OrderView>;
}
