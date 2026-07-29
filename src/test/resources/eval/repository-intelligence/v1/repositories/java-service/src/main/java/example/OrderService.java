package example;

public final class OrderService {
  public OrderView findOrder(String orderId) {
    return new OrderView(orderId, "READY");
  }

  public record OrderView(String orderId, String status) {}
}
