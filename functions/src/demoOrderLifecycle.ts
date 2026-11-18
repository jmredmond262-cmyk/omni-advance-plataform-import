export const DEMO_ORDER_TTL_MS = 24 * 60 * 60 * 1000;

export type DemoOrderExpiryState = "ACTIVE" | "EXPIRED" | "INVALID";

export function demoOrderExpiryState(
  createdAtMillis: number,
  expiresAtMillis: number | null,
  nowMillis: number
): DemoOrderExpiryState {
  if (!Number.isFinite(createdAtMillis) || createdAtMillis <= 0 ||
      !Number.isFinite(nowMillis) || nowMillis < createdAtMillis) {
    return "INVALID";
  }
  const deadlineMillis = expiresAtMillis ?? createdAtMillis + DEMO_ORDER_TTL_MS;
  if (!Number.isFinite(deadlineMillis) || deadlineMillis <= createdAtMillis) return "INVALID";
  return nowMillis >= deadlineMillis ? "EXPIRED" : "ACTIVE";
}

export function isConfirmedCashCollectionReplay(
  order: Record<string, unknown>,
  actorUid: string
): boolean {
  return order.paymentStatus === "PAGADA · EFECTIVO_DEMO" &&
    order.paymentMethod === "EFECTIVO_DEMO" &&
    order.cashCollectionConfirmedBy === actorUid;
}

export function hasValidCashCollectionReplayEvidence(
  orderId: string,
  order: Record<string, unknown>,
  actorUid: string,
  movements: Array<Record<string, unknown> | null>,
  receipt: Record<string, unknown> | null
): boolean {
  const storeId = String(order.storeId || "");
  const items = Array.isArray(order.items) ? order.items as Array<Record<string, unknown>> : [];
  const total = Number(order.total);
  if (!isConfirmedCashCollectionReplay(order, actorUid) ||
      !order.inventoryDeducted || !storeId || items.length === 0 || items.length > 30 ||
      !Number.isFinite(total) || total <= 0 || total > 50000 ||
      Number(order.cashCollectionAmount) !== total ||
      items.some((item) => {
        const quantity = Number(item.quantity);
        return !String(item.productId || "") || !Number.isInteger(quantity) || quantity < 1 || quantity > 999;
      }) ||
      new Set(items.map((item) => String(item.productId || ""))).size !== items.length ||
      movements.length !== items.length ||
      movements.some((movement, index) =>
        !movement || movement.type !== "VENTA_EFECTIVO_DEMO" ||
        movement.orderId !== orderId || movement.storeId !== storeId ||
        movement.paymentMethod !== "EFECTIVO_DEMO" || movement.environment !== "DEMO" ||
        Number(movement.quantityDelta) !== -Number(items[index]?.quantity)) ||
      !receipt || receipt.orderId !== orderId || receipt.storeId !== storeId ||
      receipt.environment !== "DEMO" || receipt.paymentMethod !== "EFECTIVO_DEMO" ||
      receipt.paymentStatus !== "PAGADA · EFECTIVO_DEMO" ||
      Number(receipt.total) !== total) {
    return false;
  }
  return true;
}
