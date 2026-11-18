import assert from "node:assert/strict";
import test from "node:test";
import {
  demoOrderDocumentId,
  demoOrderRequestFingerprint
} from "../src/index.js";

const request = {
  storeId: "demo-store-1",
  fulfillmentType: "DELIVERY",
  deliveryAddress: "Av. Siempre Viva 123",
  deliveryLatitude: -34.6,
  deliveryLongitude: -58.4,
  expectedTotal: 1250,
  expectedCashbackPolicy: {
    enabled: true,
    percentage: 2,
    capPerPurchase: 100,
    monthlyCap: 500
  },
  customerPhone: "1112345678",
  items: [{ productId: "coffee", name: "Café", price: 1250, quantity: 1 }]
};

test("order document identity is stable per buyer and idempotency key", async () => {
  const first = await demoOrderDocumentId("customer-a", "request-key-123456");
  assert.equal(first, await demoOrderDocumentId("customer-a", "request-key-123456"));
  assert.match(first, /^ord_[a-f0-9]{32}$/);
  assert.notEqual(first, await demoOrderDocumentId("customer-b", "request-key-123456"));
  assert.notEqual(first, await demoOrderDocumentId("customer-a", "request-key-654321"));
});

test("request fingerprint changes when order content changes", async () => {
  const fingerprint = await demoOrderRequestFingerprint(request);
  assert.equal(fingerprint, await demoOrderRequestFingerprint({
    ...request,
    expectedCashbackPolicy: { monthlyCap: 500, capPerPurchase: 100, percentage: 2, enabled: true }
  }));
  assert.notEqual(fingerprint, await demoOrderRequestFingerprint({
    ...request,
    items: [{ ...request.items[0], quantity: 2 }]
  }));
  assert.notEqual(fingerprint, await demoOrderRequestFingerprint({
    ...request,
    deliveryAddress: "Av. Siempre Viva 456"
  }));
});
