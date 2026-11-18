import assert from "node:assert/strict";
import test from "node:test";
import {
  buildOrderContextQuery,
  parseOrderContextRequest
} from "../src/index.js";

test("order context requires both an order and its store", () => {
  assert.deepEqual(
    parseOrderContextRequest({ orderId: "ord_12345678", storeId: "demo_store" }),
    { orderId: "ord_12345678", storeId: "demo_store" }
  );
  assert.throws(() => parseOrderContextRequest({ orderId: "ord_12345678" }), /no son válidos/);
  assert.throws(() => parseOrderContextRequest({ orderId: "short", storeId: "demo_store" }), /no son válidos/);
});

test("order context queries are bounded and match the exact orderId", () => {
  const query = buildOrderContextQuery("ord_12345678", "inventoryMovements");
  assert.deepEqual(query.structuredQuery.from, [{ collectionId: "inventoryMovements" }]);
  assert.deepEqual(query.structuredQuery.where.fieldFilter, {
    field: { fieldPath: "orderId" },
    op: "EQUAL",
    value: { stringValue: "ord_12345678" }
  });
  assert.equal(query.structuredQuery.limit, 100);
  assert.throws(() => buildOrderContextQuery("bad", "walletOperations"), /consulta de trazabilidad/);
});
