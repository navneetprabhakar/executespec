from dataclasses import dataclass


@dataclass(frozen=True)
class OrderEvent:
    order_id: str
    status: str


def publish_order_status(event: OrderEvent) -> str:
    return f"orders.{event.status.lower()}:{event.order_id}"
