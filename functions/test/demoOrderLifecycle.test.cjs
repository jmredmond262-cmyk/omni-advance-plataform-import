const assert = require("node:assert/strict");
const test = require("node:test");
const {
  DEMO_ORDER_TTL_MS,
  demoOrderExpiryState,
  hasValidCashCollectionReplayEvidence,
  isConfirmedCashCollectionReplay
} = require("../lib/demoOrderLifecycle.js");

test("pending DEMO order expires exactly at the 24-hour deadline", () => {
  const createdAt = 1_700_000_000_000;
  const deadline = createdAt + DEMO_ORDER_TTL_MS;
  assert.equal(demoOrderExpiryState(createdAt, null, deadline - 1), "ACTIVE");
  assert.equal(demoOrderExpiryState(createdAt, null, deadline), "EXPIRED");
  assert.equal(demoOrderExpiryState(createdAt, null, deadline + 1), "EXPIRED");
});

test("stored expiration is honored and invalid timestamps fail closed", () => {
  const createdAt = 1_700_000_000_000;
  assert.equal(demoOrderExpiryState(createdAt, createdAt + 1_000, createdAt + 999), "ACTIVE");
  assert.equal(demoOrderExpiryState(createdAt, createdAt + 1_000, createdAt + 1_000), "EXPIRED");
  assert.equal(demoOrderExpiryState(createdAt, createdAt - 1, createdAt), "INVALID");
  assert.equal(demoOrderExpiryState(createdAt, createdAt, createdAt), "INVALID");
  assert.equal(demoOrderExpiryState(Number.NaN, null, createdAt), "INVALID");
  assert.equal(demoOrderExpiryState(createdAt + 1, null, createdAt), "INVALID");
});

test("cash collection retries validate the saved sale ledger without depending on current stock", () => {
  const orderId = "order-123";
  const actorUid = "merchant-1";
  const order = {
    storeId: "store-1",
    paymentStatus: "PAGADA · EFECTIVO_DEMO",
    paymentMethod: "EFECTIVO_DEMO",
    cashCollectionConfirmedBy: actorUid,
    cashCollectionAmount: 1250,
    inventoryDeducted: true,
    total: 1250,
    items: [{ productId: "coffee", quantity: 2 }]
  };
  const movement = {
    type: "VENTA_EFECTIVO_DEMO",
    orderId,
    storeId: order.storeId,
    paymentMethod: "EFECTIVO_DEMO",
    environment: "DEMO",
    quantityDelta: -2
  };
  const receipt = {
    orderId,
    storeId: order.storeId,
    environment: "DEMO",
    paymentMethod: "EFECTIVO_DEMO",
    paymentStatus: "PAGADA · EFECTIVO_DEMO",
    total: 1250
  };

  assert.equal(isConfirmedCashCollectionReplay(order, actorUid), true);
  assert.equal(hasValidCashCollectionReplayEvidence(orderId, order, actorUid, [movement], receipt), true);
  assert.equal(hasValidCashCollectionReplayEvidence(orderId, order, actorUid, [], receipt), false);
  assert.equal(hasValidCashCollectionReplayEvidence(orderId, order, "different-merchant", [movement], receipt), false);
  assert.equal(hasValidCashCollectionReplayEvidence(orderId, order, actorUid, [movement], { ...receipt, total: 1251 }), false);
});
