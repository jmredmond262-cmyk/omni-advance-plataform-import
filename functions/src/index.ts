import { getApps, initializeApp } from "firebase-admin/app";
import { FieldValue, getFirestore } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { createHash, randomBytes, randomInt, randomUUID } from "node:crypto";

if (getApps().length === 0) initializeApp();
const db = getFirestore();
const REGION = "us-central1";
const INITIAL_BALANCE = 50000;
const MAX_TOPUP_PER_CALL = 50000;
const MAX_TOPUP_LIFETIME = 150000;

function requireUid(request: { auth?: { uid: string } | null }): string {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Iniciá sesión para usar OMNI Pay DEMO.");
  return uid;
}
function parseAmount(value: unknown): number {
  const amount = Number(value);
  if (!Number.isFinite(amount) || amount <= 0 || amount > 10000000) {
    throw new HttpsError("invalid-argument", "El importe debe ser mayor que cero y válido.");
  }
  return Math.round(amount * 100) / 100;
}
function accountRef(uid: string) { return db.collection("walletAccounts").doc(uid); }
function safeName(value: unknown): string {
  return typeof value === "string" && value.trim() ? value.trim().slice(0, 80) : "Usuario OMNI";
}

export const ensureDemoWallet = onCall({ region: REGION }, async (request) => {
  const uid = requireUid(request);
  const ref = accountRef(uid);
  const alias = "omni" + uid.replace(/[^a-zA-Z0-9]/g, "").slice(0, 10).toLowerCase();
  const aliasRef = db.collection("walletAliases").doc(alias);
  const displayName = safeName(request.auth?.token.name);
  const result = await db.runTransaction(async (tx) => {
    const existing = await tx.get(ref);
    if (existing.exists) return existing.data()!;
    const aliasOwner = await tx.get(aliasRef);
    if (aliasOwner.exists && aliasOwner.get("uid") !== uid) {
      throw new HttpsError("already-exists", "No se pudo reservar el alias automático. Contactá soporte DEMO.");
    }
    const cvu = "00000031" + Array.from({ length: 14 }, () => randomInt(0, 10)).join("");
    const account = {
      uid, displayName, alias, cvu, balance: INITIAL_BALANCE,
      cashbackTotal: 0, topUpTotal: 0, currency: "OM$", environment: "DEMO",
      createdAt: FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp()
    };
    tx.create(ref, account);
    tx.set(aliasRef, { uid, createdAt: FieldValue.serverTimestamp() });
    return account;
  });
  return { uid, alias: result.alias, cvu: result.cvu, balance: result.balance, currency: "OM$", environment: "DEMO" };
});

async function resolveRecipient(identifier: string): Promise<string> {
  const clean = identifier.trim();
  if (!clean) throw new HttpsError("invalid-argument", "Ingresá un alias o CVU.");
  if (/^[A-Za-z0-9_-]{20,}$/.test(clean)) {
    const direct = await accountRef(clean).get();
    if (direct.exists) return clean;
  }
  const alias = await db.collection("walletAliases").doc(clean.toLowerCase()).get();
  if (alias.exists) return String(alias.get("uid"));
  const byCvu = await db.collection("walletAccounts").where("cvu", "==", clean).limit(1).get();
  if (!byCvu.empty) return byCvu.docs[0].id;
  throw new HttpsError("not-found", "No encontramos una cuenta OMNI Pay DEMO con ese alias o CVU.");
}

async function executeTransfer(senderUid: string, recipientUid: string, amountValue: unknown, kind: "TRANSFERENCIA" | "QR", operationIdValue: unknown) {
  if (senderUid === recipientUid) throw new HttpsError("failed-precondition", "No podés transferirte a tu propia cuenta.");
  const amount = parseAmount(amountValue);
  const operationId = typeof operationIdValue === "string" && /^[A-Za-z0-9_-]{12,80}$/.test(operationIdValue)
    ? operationIdValue : randomUUID();
  const senderRef = accountRef(senderUid);
  const recipientRef = accountRef(recipientUid);
  const senderTx = senderRef.collection("transactions").doc(operationId);
  const recipientTx = recipientRef.collection("transactions").doc(operationId);
  return db.runTransaction(async (tx) => {
    const [senderSnap, recipientSnap, senderTxSnap, recipientTxSnap] = await Promise.all([
      tx.get(senderRef), tx.get(recipientRef), tx.get(senderTx), tx.get(recipientTx)
    ]);
    if (senderTxSnap.exists || recipientTxSnap.exists) return { operationId, status: "APROBADA · DEMO", duplicate: true };
    if (!senderSnap.exists || !recipientSnap.exists) throw new HttpsError("not-found", "Una de las cuentas DEMO no está inicializada.");
    const senderBalance = Number(senderSnap.get("balance") || 0);
    const recipientBalance = Number(recipientSnap.get("balance") || 0);
    if (senderBalance < amount) throw new HttpsError("failed-precondition", "Saldo virtual insuficiente.");
    const senderName = safeName(requestName(senderSnap.data()));
    const recipientName = safeName(requestName(recipientSnap.data()));
    const now = FieldValue.serverTimestamp();
    tx.update(senderRef, { balance: Math.round((senderBalance - amount) * 100) / 100, updatedAt: now });
    tx.update(recipientRef, { balance: Math.round((recipientBalance + amount) * 100) / 100, updatedAt: now });
    tx.create(senderTx, {
      operationId, type: kind === "QR" ? "QR_PAGO" : "TRANSFERENCIA_ENVIADA",
      title: kind === "QR" ? "Pago QR enviado" : "Transferencia enviada",
      peerUid: recipientUid, peerName: recipientName, amount: -amount,
      status: "APROBADA · DEMO", environment: "DEMO", createdAt: now
    });
    tx.create(recipientTx, {
      operationId, type: kind === "QR" ? "QR_COBRO" : "TRANSFERENCIA_RECIBIDA",
      title: kind === "QR" ? "Cobro QR recibido" : "Transferencia recibida",
      peerUid: senderUid, peerName: senderName, amount,
      status: "APROBADA · DEMO", environment: "DEMO", createdAt: now
    });
    return { operationId, status: "APROBADA · DEMO", duplicate: false };
  });
}
function requestName(data: Record<string, unknown> | undefined): unknown { return data?.displayName; }

export const transferDemoFunds = onCall({ region: REGION }, async (request) => {
  const uid = requireUid(request);
  const identifier = String(request.data?.recipientIdentifier || "");
  const recipientUid = await resolveRecipient(identifier);
  return executeTransfer(uid, recipientUid, request.data?.amount, "TRANSFERENCIA", request.data?.operationId);
});

export const payDemoQr = onCall({ region: REGION }, async (request) => {
  const uid = requireUid(request);
  const payload = String(request.data?.qrPayload || "");
  const parts = payload.split("|");
  if (parts.length !== 3 || parts[0] !== "OMNI-DEMO" || !parts[1] || !parts[2]) {
    throw new HttpsError("invalid-argument", "El QR no pertenece a una cuenta OMNI Pay DEMO.");
  }
  const recipientUid = parts[1];
  const recipient = await accountRef(recipientUid).get();
  if (!recipient.exists || recipient.get("alias") !== parts[2]) {
    throw new HttpsError("not-found", "El QR está vencido o no corresponde a una cuenta válida.");
  }
  return executeTransfer(uid, recipientUid, request.data?.amount, "QR", request.data?.operationId);
});

export const topUpDemoWallet = onCall({ region: REGION }, async (request) => {
  const uid = requireUid(request);
  const amount = parseAmount(request.data?.amount);
  if (amount > MAX_TOPUP_PER_CALL) throw new HttpsError("invalid-argument", "La carga máxima por operación es OM$ 50.000.");
  const ref = accountRef(uid);
  const operationId = typeof request.data?.operationId === "string" && /^[A-Za-z0-9_-]{12,80}$/.test(request.data.operationId)
    ? request.data.operationId : randomUUID();
  const txRef = ref.collection("transactions").doc(operationId);
  return db.runTransaction(async (tx) => {
    const [account, existingTx] = await Promise.all([tx.get(ref), tx.get(txRef)]);
    if (existingTx.exists) return { operationId, status: "APROBADA · DEMO", duplicate: true };
    if (!account.exists) throw new HttpsError("failed-precondition", "Primero inicializá tu cuenta OMNI Pay DEMO.");
    const topUpTotal = Number(account.get("topUpTotal") || 0);
    if (topUpTotal + amount > MAX_TOPUP_LIFETIME) throw new HttpsError("resource-exhausted", "Alcanzaste el límite acumulado de cargas DEMO de OM$ 150.000.");
    tx.update(ref, {
      balance: Math.round((Number(account.get("balance") || 0) + amount) * 100) / 100,
      topUpTotal: Math.round((topUpTotal + amount) * 100) / 100,
      updatedAt: FieldValue.serverTimestamp()
    });
    tx.create(txRef, {
      operationId, type: "CARGA_SALDO", title: "Carga de saldo DEMO",
      peerUid: uid, peerName: "Simulador OMNI", amount,
      status: "APROBADA · DEMO", environment: "DEMO", createdAt: FieldValue.serverTimestamp()
    });
    return { operationId, status: "APROBADA · DEMO", duplicate: false };
  });
});


function deliveryDistanceKm(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const radians = (degrees: number) => degrees * Math.PI / 180;
  const dLat = radians(lat2 - lat1);
  const dLon = radians(lon2 - lon1);
  const rawA = Math.sin(dLat / 2) ** 2 +
    Math.cos(radians(lat1)) * Math.cos(radians(lat2)) * Math.sin(dLon / 2) ** 2;
  const a = Math.min(1, Math.max(0, rawA));
  return 6371.0088 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

function validCoordinates(latitude: unknown, longitude: unknown): latitude is number {
  return typeof latitude === "number" && Number.isFinite(latitude) && latitude >= -90 && latitude <= 90 &&
    typeof longitude === "number" && Number.isFinite(longitude) && longitude >= -180 && longitude <= 180;
}

/**
 * Crea pedidos DEMO en servidor. La creación directa desde clientes queda bloqueada
 * para que nadie pueda falsificar coordenadas ni eludir el radio de entrega.
 */
export const createDemoStoreOrder = onCall({ region: REGION }, async (request) => {
  const customerUid = requireUid(request);
  const data = request.data || {};
  const storeId = String(data.storeId || "").trim();
  const fulfillmentType = String(data.fulfillmentType || "RETIRO");
  const address = String(data.deliveryAddress || "").trim();
  if (!/^[A-Za-z0-9_-]{1,120}$/.test(storeId) ||
      !["RETIRO", "DELIVERY"].includes(fulfillmentType)) {
    throw new HttpsError("invalid-argument", "El comercio o la modalidad del pedido no son válidos.");
  }
  if (fulfillmentType === "DELIVERY" && (address.length < 5 || address.length > 240)) {
    throw new HttpsError("invalid-argument", "Ingresá una dirección de entrega válida.");
  }
  const items = Array.isArray(data.items) ? data.items as Array<Record<string, unknown>> : [];
  if (items.length < 1 || items.length > 30) {
    throw new HttpsError("invalid-argument", "El pedido debe contener entre 1 y 30 productos.");
  }
  const profileRef = db.collection("merchantProfiles").doc(storeId);
  const profileSnap = await profileRef.get();
  if (!profileSnap.exists || profileSnap.get("environment") !== "DEMO" ||
      profileSnap.get("seedVersion") !== 1 || profileSnap.get("storeId") !== storeId) {
    throw new HttpsError("failed-precondition", "El comercio no está habilitado para pedidos DEMO.");
  }
  const merchantUid = String(profileSnap.get("ownerUid") || "");
  if (!merchantUid || merchantUid === customerUid) {
    throw new HttpsError("failed-precondition", "El comercio todavía no tiene un titular habilitado para recibir pedidos.");
  }
  if (fulfillmentType === "RETIRO" && profileSnap.get("pickupEnabled") === false) {
    throw new HttpsError("failed-precondition", "El comercio no habilitó el retiro.");
  }
  let deliveryLatitude: number | null = null;
  let deliveryLongitude: number | null = null;
  if (fulfillmentType === "DELIVERY") {
    const merchantLat = profileSnap.get("merchantLatitude");
    const merchantLon = profileSnap.get("merchantLongitude");
    const buyerLat = data.deliveryLatitude;
    const buyerLon = data.deliveryLongitude;
    if (profileSnap.get("deliveryEnabled") !== true ||
        !validCoordinates(merchantLat, merchantLon) || !validCoordinates(buyerLat, buyerLon)) {
      throw new HttpsError("failed-precondition", "El comercio o tu dispositivo todavía no tienen una ubicación válida para verificar la cobertura.");
    }
    const distance = deliveryDistanceKm(merchantLat as number, merchantLon as number, buyerLat as number, buyerLon as number);
    const radius = Number(profileSnap.get("deliveryRadiusKm"));
    if (!Number.isFinite(radius) || radius <= 0 || distance > radius + 0.05) {
      throw new HttpsError("out-of-range", "La dirección está fuera del radio de delivery de este comercio.");
    }
    deliveryLatitude = buyerLat as number;
    deliveryLongitude = buyerLon as number;
  }
  const catalog = Array.isArray(profileSnap.get("inventory"))
    ? profileSnap.get("inventory") as Array<Record<string, unknown>> : [];
  let subtotal = 0;
  const normalizedItems: Array<{productId:string;name:string;price:number;quantity:number}> = [];
  const seen = new Set<string>();
  for (const line of items) {
    const productId = String(line.productId || "");
    const quantity = Number(line.quantity);
    const price = Number(line.price);
    if (!productId || seen.has(productId) || !Number.isInteger(quantity) || quantity < 1 || quantity > 999 ||
        !Number.isFinite(price) || price <= 0) {
      throw new HttpsError("invalid-argument", "Hay productos o cantidades inválidas en el pedido.");
    }
    seen.add(productId);
    const current = catalog.find((item) => String(item.id || "") === productId);
    if (!current || current.inStock !== true || Math.abs(Number(current.price) - price) >= 0.005) {
      throw new HttpsError("failed-precondition", "Cambió el precio o la disponibilidad de un producto. Actualizá el carrito.");
    }
    const name = String(current.name || "");
    if (!name || name.length > 80 || String(line.name || "") !== name) {
      throw new HttpsError("failed-precondition", "Cambió el nombre de un producto. Actualizá el carrito.");
    }
    normalizedItems.push({ productId, name, price, quantity });
    subtotal += price * quantity;
  }
  subtotal = Math.round(subtotal * 100) / 100;
  const minimum = Number(profileSnap.get("minimumOrderAmount") ?? 0);
  const freeFromRaw = profileSnap.get("freeDeliveryFrom");
  const freeFrom = freeFromRaw == null ? null : Number(freeFromRaw);
  const configuredDeliveryFee = Number(profileSnap.get("deliveryFee") ?? 0);
  if (!Number.isFinite(minimum) || minimum < 0 || minimum > 50000 ||
      (freeFrom !== null && (!Number.isFinite(freeFrom) || freeFrom < 0 || freeFrom > 50000)) ||
      !Number.isFinite(configuredDeliveryFee) || configuredDeliveryFee < 0 || configuredDeliveryFee > 50000) {
    throw new HttpsError("failed-precondition", "La configuración de compra mínima o envío del comercio no es válida.");
  }
  if (subtotal <= 0 || subtotal > 50000 || subtotal < minimum) {
    throw new HttpsError("failed-precondition", "El pedido no alcanza la compra mínima o supera el máximo DEMO.");
  }
  const deliveryFee = fulfillmentType === "DELIVERY" && !(freeFrom !== null && subtotal >= freeFrom)
    ? configuredDeliveryFee : 0;
  const total = Math.round((subtotal + deliveryFee) * 100) / 100;
  const expectedTotal = Number(data.expectedTotal);
  if (!Number.isFinite(expectedTotal) || Math.abs(expectedTotal - total) >= 0.005) {
    throw new HttpsError("failed-precondition", "El total cambió desde la revisión del carrito. Actualizá el pedido antes de confirmar.");
  }
  const cashback = profileSnap.get("cashback");
  const cashbackPolicy = cashback && typeof cashback === "object" ? cashback : {
    enabled: false, percentage: 0, capPerPurchase: null, monthlyCap: null
  };
  const expectedPolicy = data.expectedCashbackPolicy as Record<string, unknown> | undefined;
  const actualPolicy = cashbackPolicy as Record<string, unknown>;
  if (!expectedPolicy ||
      expectedPolicy.enabled !== actualPolicy.enabled ||
      Number(expectedPolicy.percentage || 0) !== Number(actualPolicy.percentage || 0) ||
      (expectedPolicy.capPerPurchase ?? null) !== (actualPolicy.capPerPurchase ?? null) ||
      (expectedPolicy.monthlyCap ?? null) !== (actualPolicy.monthlyCap ?? null)) {
    throw new HttpsError("failed-precondition", "El comercio actualizó el cashback desde la revisión. Volvé a revisar las condiciones.");
  }
  const orderRef = db.collection("storeOrders").doc();
  const qrNonce = randomUUID().replace(/-/g, "") + randomBytes(8).toString("hex");
  await orderRef.create({
    storeId, storeName: String(profileSnap.get("storeName") || "Comercio DEMO"),
    environment: "DEMO", merchantUid, customerUid,
    customerName: safeName(request.auth?.token.name),
    customerPhone: String(data.customerPhone || "").slice(0, 40),
    items: normalizedItems, subtotal, deliveryFee, total, cashbackPolicy,
    status: "RECIBIDO", paymentStatus: "PENDIENTE", paymentMethod: "PAGO_AL_RETIRAR",
    fulfillmentType, deliveryAddress: fulfillmentType === "DELIVERY" ? address : "",
    ...(deliveryLatitude != null && deliveryLongitude != null
      ? { deliveryLatitude, deliveryLongitude } : {}),
    qrNonce, operationId: orderRef.id,
    createdAt: FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp()
  });
  return { orderId: orderRef.id, qrNonce, subtotal, deliveryFee, total, items: normalizedItems,
    storeId, storeName: String(profileSnap.get("storeName") || "Comercio DEMO"), merchantUid,
    cashbackPolicy, deliveryLatitude, deliveryLongitude };
});

/**
 * Pasarela de tarjeta ficticia. No recibe datos de tarjeta ni mueve fondos;
 * toda aprobación se valida y registra desde servidor, incluida la cobertura delivery.
 */
export const simulateDemoCardPayment = onCall({ region: REGION }, async (request) => {
  const customerUid = requireUid(request);
  const orderId = String(request.data?.orderId || "").trim();
  const amount = Number(request.data?.amount);
  const cardBrand = String(request.data?.cardBrand || "");
  const approved = request.data?.approved === true;
  if (!/^[A-Za-z0-9_-]{8,120}$/.test(orderId) || !Number.isFinite(amount) ||
      amount <= 0 || amount > 50000 || !["VISA", "MASTERCARD"].includes(cardBrand)) {
    throw new HttpsError("invalid-argument", "El intento de tarjeta DEMO no es válido.");
  }
  const orderRef = db.collection("storeOrders").doc(orderId);
  const paymentRef = db.collection("demoCardPayments").doc(`carddemo_${randomUUID().replace(/-/g, "")}`);
  return db.runTransaction(async (tx) => {
    const orderSnap = await tx.get(orderRef);
    if (!orderSnap.exists) throw new HttpsError("not-found", "El pedido ya no está disponible.");
    const order = orderSnap.data()!;
    if (order.environment !== "DEMO" || order.customerUid !== customerUid) {
      throw new HttpsError("permission-denied", "Solo el comprador puede simular el pago de este pedido DEMO.");
    }
    if (order.paymentStatus !== "PENDIENTE" ||
        !["RECIBIDO", "ACEPTADO", "EN_PREPARACION", "LISTO", "EN_CAMINO"].includes(String(order.status || "")) ||
        Math.abs(Number(order.total || 0) - amount) >= 0.005) {
      throw new HttpsError("failed-precondition", "El pedido ya no admite este pago o cambió su importe.");
    }
    const createdAt = order.createdAt?.toDate?.();
    if (!createdAt || Date.now() < createdAt.getTime() || Date.now() - createdAt.getTime() >= 24 * 60 * 60 * 1000) {
      throw new HttpsError("deadline-exceeded", "El pedido venció. Solicitá uno nuevo.");
    }
    const profileRef = db.collection("merchantProfiles").doc(String(order.storeId || ""));
    const profileSnap = await tx.get(profileRef);
    if (!profileSnap.exists || profileSnap.get("environment") !== "DEMO" ||
        profileSnap.get("seedVersion") !== 1 || profileSnap.get("storeId") !== order.storeId ||
        profileSnap.get("ownerUid") !== order.merchantUid) {
      throw new HttpsError("failed-precondition", "El comercio ya no está habilitado para este pedido.");
    }
    const orderItems = Array.isArray(order.items) ? order.items as Array<Record<string, unknown>> : [];
    const catalog = Array.isArray(profileSnap.get("inventory")) ? profileSnap.get("inventory") as Array<Record<string, unknown>> : [];
    let verifiedSubtotal = 0;
    const seenProducts = new Set<string>();
    if (orderItems.length === 0 || orderItems.length > 30) {
      throw new HttpsError("failed-precondition", "El detalle del pedido ya no es válido.");
    }
    for (const line of orderItems) {
      const productId = String(line.productId || "");
      const quantity = Number(line.quantity);
      const price = Number(line.price);
      const product = catalog.find((item) => String(item.id || "") === productId);
      const currentPrice = Number(product?.price);
      if (!productId || seenProducts.has(productId) || !product || product.inStock !== true ||
          !Number.isInteger(quantity) || quantity < 1 || quantity > 999 ||
          !Number.isFinite(price) || price <= 0 || !Number.isFinite(currentPrice) ||
          Math.abs(currentPrice - price) >= 0.005 || String(line.name || "") !== String(product.name || "")) {
        throw new HttpsError("failed-precondition", "El catálogo o el detalle del pedido cambió. No se registró el pago.");
      }
      seenProducts.add(productId);
      verifiedSubtotal += price * quantity;
    }
    const subtotal = Math.round(verifiedSubtotal * 100) / 100;
    const savedSubtotal = Number(order.subtotal ?? subtotal);
    const savedFee = Number(order.deliveryFee ?? 0);
    const minimum = Number(profileSnap.get("minimumOrderAmount") ?? 0);
    const freeFromRaw = profileSnap.get("freeDeliveryFrom");
    const freeFrom = freeFromRaw == null ? null : Number(freeFromRaw);
    const configuredDeliveryFee = Number(profileSnap.get("deliveryFee") ?? 0);
    if (!Number.isFinite(minimum) || minimum < 0 || minimum > 50000 ||
        (freeFrom !== null && (!Number.isFinite(freeFrom) || freeFrom < 0 || freeFrom > 50000)) ||
        !Number.isFinite(configuredDeliveryFee) || configuredDeliveryFee < 0 || configuredDeliveryFee > 50000 ||
        !Number.isFinite(savedFee) || savedFee < 0 || savedFee > 50000 ||
        !Number.isFinite(savedSubtotal) || Math.abs(subtotal - savedSubtotal) >= 0.005 ||
        subtotal < minimum || Math.abs(subtotal + savedFee - amount) >= 0.005) {
      throw new HttpsError("failed-precondition", "El total, la compra mínima o el envío ya no coinciden. No se registró el pago.");
    }
    const fulfillment = String(order.fulfillmentType || "RETIRO");
    if (!["RETIRO", "DELIVERY"].includes(fulfillment) ||
        (fulfillment === "RETIRO" && profileSnap.get("pickupEnabled") === false) ||
        (fulfillment === "DELIVERY" && (profileSnap.get("deliveryEnabled") !== true || !String(order.deliveryAddress || "").trim()))) {
      throw new HttpsError("failed-precondition", "El comercio ya no ofrece esta modalidad de entrega. No se registró el pago.");
    }
    const expectedFee = fulfillment === "DELIVERY" && !(freeFrom !== null && subtotal >= freeFrom)
      ? configuredDeliveryFee : 0;
    if (Math.abs(expectedFee - savedFee) >= 0.005) {
      throw new HttpsError("failed-precondition", "La tarifa de envío cambió. No se registró el pago.");
    }
    if (String(order.fulfillmentType || "RETIRO") === "DELIVERY") {
      const merchantLat = profileSnap.get("merchantLatitude");
      const merchantLon = profileSnap.get("merchantLongitude");
      const buyerLat = order.deliveryLatitude;
      const buyerLon = order.deliveryLongitude;
      const radius = Number(profileSnap.get("deliveryRadiusKm"));
      if (!validCoordinates(merchantLat, merchantLon) || !validCoordinates(buyerLat, buyerLon) ||
          !Number.isFinite(radius) || radius <= 0 ||
          deliveryDistanceKm(merchantLat as number, merchantLon as number, buyerLat as number, buyerLon as number) > radius + 0.05) {
        throw new HttpsError("failed-precondition", "La ubicación del pedido ya no supera la validación de cobertura. No se registró el pago.");
      }
    }
    const status = approved ? "APROBADA · DEMO" : "RECHAZADA · DEMO";
    tx.create(paymentRef, {
      paymentId: paymentRef.id, orderId, customerUid, merchantUid: String(order.merchantUid),
      amount, cardBrand, provider: "OMNI_DEMO_GATEWAY", status, environment: "DEMO",
      createdAt: FieldValue.serverTimestamp()
    });
    if (approved) tx.update(orderRef, {
      paymentStatus: "PAGADA · DEMO", paymentMethod: "TARJETA_DEMO",
      cardPaymentId: paymentRef.id, updatedAt: FieldValue.serverTimestamp()
    });
    return { message: approved ? "PAGO DE TARJETA SIMULADO · APROBADO · DEMO" : "PAGO DE TARJETA SIMULADO · RECHAZADO · DEMO", approved };
  });
});

/**
 * Confirma un pedido DEMO y acredita cashback en una única transacción confiable.
 * El cliente solo envía el ID del pedido: importe, titularidad y condiciones salen de Firestore.
 */
export const payStoreOrderDemo = onCall({ region: REGION }, async (request) => {
  const customerUid = requireUid(request);
  const orderId = String(request.data?.orderId || "").trim();
  if (!/^[A-Za-z0-9_-]{8,120}$/.test(orderId)) {
    throw new HttpsError("invalid-argument", "El identificador del pedido no es válido.");
  }

  const orderRef = db.collection("storeOrders").doc(orderId);
  const operationId = `orderpay_${orderId}`;
  const operationRef = db.collection("walletOperations").doc(operationId);
  const customerRef = accountRef(customerUid);
  const customerPaymentRef = customerRef.collection("transactions").doc(operationId);

  return db.runTransaction(async (tx) => {
    const [orderSnap, customerSnap, operationSnap, customerPaymentSnap] = await Promise.all([
      tx.get(orderRef), tx.get(customerRef), tx.get(operationRef), tx.get(customerPaymentRef)
    ]);
    if (!orderSnap.exists) throw new HttpsError("not-found", "El pedido ya no está disponible.");
    const order = orderSnap.data()!;
    if (order.customerUid !== customerUid) {
      throw new HttpsError("permission-denied", "Solo quien creó el pedido puede pagarlo.");
    }
    const merchantUid = String(order.merchantUid || "");
    const storeId = String(order.storeId || "");
    if (!merchantUid || !storeId || merchantUid === customerUid || order.environment !== "DEMO") {
      throw new HttpsError("failed-precondition", "El pedido no pertenece a un comercio DEMO válido.");
    }
    const merchantRef = accountRef(merchantUid);
    const merchantPaymentRef = merchantRef.collection("transactions").doc(operationId);
    const cashbackRef = db.collection("cashbackAccruals").doc(orderId);
    const cashbackTransactionRef = customerRef.collection("transactions").doc(`cashback_${orderId}`);
    const [existingMerchantPayment, existingCashback, existingCashbackTransaction] = await Promise.all([
      tx.get(merchantPaymentRef), tx.get(cashbackRef), tx.get(cashbackTransactionRef)
    ]);

    if (operationSnap.exists || customerPaymentSnap.exists ||
        existingMerchantPayment.exists || existingCashback.exists || existingCashbackTransaction.exists) {
      const previous = operationSnap.exists ? operationSnap.data()! : null;
      const existingCashbackAmount = existingCashback.exists
        ? Number(existingCashback.get("amount") ?? 0) : Number.NaN;
      const cashbackLedgerValid = Number.isFinite(existingCashbackAmount) &&
        existingCashbackAmount >= 0 &&
        (existingCashbackAmount > 0
          ? existingCashbackTransaction.exists &&
            Number(existingCashbackTransaction.get("amount")) === existingCashbackAmount
          : !existingCashbackTransaction.exists);
      const paymentLedgerValid = previous !== null && customerPaymentSnap.exists && existingMerchantPayment.exists &&
        Number(customerPaymentSnap.get("amount")) === Number(previous.amount) &&
        Number(existingMerchantPayment.get("amount")) === Number(previous.amount);
      if (!previous || previous.orderId !== orderId || previous.senderUid !== customerUid ||
          previous.recipientUid !== merchantUid || previous.kind !== "QR_PAYMENT" ||
          !paymentLedgerValid || !existingCashback.exists || !cashbackLedgerValid ||
          order.paymentStatus !== "PAGADA · DEMO") {
        throw new HttpsError("data-loss", "El pedido tiene un registro de pago incompleto o inconsistente. No se volverá a debitar.");
      }
      return {
        operationId, status: "PEDIDO YA PAGADO · DEMO",
        duplicate: true, cashbackAmount: existingCashbackAmount
      };
    }

    if (!customerSnap.exists) throw new HttpsError("failed-precondition", "Tu billetera DEMO no está inicializada.");
    if (order.paymentStatus !== "PENDIENTE" ||
        !["RECIBIDO", "ACEPTADO", "EN_PREPARACION", "LISTO", "EN_CAMINO"].includes(String(order.status || ""))) {
      throw new HttpsError("failed-precondition", "El pedido ya fue pagado o está cerrado.");
    }
    const createdAt = order.createdAt?.toDate?.();
    if (!createdAt || Date.now() < createdAt.getTime() ||
        Date.now() - createdAt.getTime() > 24 * 60 * 60 * 1000) {
      throw new HttpsError("deadline-exceeded", "El pedido venció. Solicitá al comercio uno nuevo.");
    }

    const profileRef = db.collection("merchantProfiles").doc(storeId);
    const merchantSnap = await tx.get(merchantRef);
    const profileSnap = await tx.get(profileRef);
    if (!merchantSnap.exists || !profileSnap.exists ||
        profileSnap.get("ownerUid") !== merchantUid ||
        profileSnap.get("environment") !== "DEMO" ||
        profileSnap.get("seedVersion") !== 1 ||
        profileSnap.get("storeId") !== storeId) {
      throw new HttpsError("failed-precondition", "La billetera o titularidad del comercio no está disponible.");
    }

    const amount = Number(order.total);
    if (!Number.isFinite(amount) || amount <= 0 || amount > 50000) {
      throw new HttpsError("failed-precondition", "El total del pedido no es válido.");
    }
    const orderItems = Array.isArray(order.items) ? order.items as Array<Record<string, unknown>> : [];
    if (String(order.fulfillmentType || "RETIRO") === "DELIVERY") {
      const merchantLat = profileSnap.get("merchantLatitude");
      const merchantLon = profileSnap.get("merchantLongitude");
      const buyerLat = order.deliveryLatitude;
      const buyerLon = order.deliveryLongitude;
      const radius = Number(profileSnap.get("deliveryRadiusKm"));
      if (!validCoordinates(merchantLat, merchantLon) || !validCoordinates(buyerLat, buyerLon) ||
          !Number.isFinite(radius) || radius <= 0 ||
          deliveryDistanceKm(merchantLat as number, merchantLon as number, buyerLat as number, buyerLon as number) > radius + 0.05) {
        throw new HttpsError("failed-precondition", "La ubicación del pedido ya no supera la validación de cobertura. No se debitó el pago.");
      }
    }

    const catalog = Array.isArray(profileSnap.get("inventory"))
      ? profileSnap.get("inventory") as Array<Record<string, unknown>> : [];
    if (orderItems.length === 0 || orderItems.length > 30 || catalog.length === 0) {
      throw new HttpsError("failed-precondition", "El catálogo o los artículos del pedido ya no están disponibles.");
    }
    const seenProducts = new Set<string>();
    let verifiedTotal = 0;
    for (const line of orderItems) {
      const productId = String(line.productId || "");
      const quantity = Number(line.quantity);
      const price = Number(line.price);
      if (!productId || seenProducts.has(productId) || !Number.isInteger(quantity) ||
          quantity < 1 || quantity > 999 || !Number.isFinite(price) || price <= 0) {
        throw new HttpsError("failed-precondition", "El detalle del pedido no es válido.");
      }
      seenProducts.add(productId);
      const currentProduct = catalog.find((item) => String(item.id || "") === productId);
      const currentPrice = Number(currentProduct?.price);
      if (!currentProduct || currentProduct.inStock !== true || !Number.isFinite(currentPrice) ||
          Math.abs(currentPrice - price) >= 0.005 ||
          String(line.name || "") !== String(currentProduct.name || "")) {
        throw new HttpsError("failed-precondition", "Cambió el nombre, precio o disponibilidad de un producto. Actualizá el pedido.");
      }
      verifiedTotal += price * quantity;
    }
    const subtotal = order.subtotal == null ? verifiedTotal : Number(order.subtotal);
    const deliveryFee = order.deliveryFee == null ? 0 : Number(order.deliveryFee);
    const fulfillmentType = String(order.fulfillmentType || "RETIRO");
    const minimumOrderAmount = Number(profileSnap.get("minimumOrderAmount") ?? 0);
    const freeFromRaw = profileSnap.get("freeDeliveryFrom");
    const freeFrom = freeFromRaw == null ? null : Number(freeFromRaw);
    const configuredDeliveryFee = Number(profileSnap.get("deliveryFee") ?? 0);
    if (!Number.isFinite(minimumOrderAmount) || minimumOrderAmount < 0 || minimumOrderAmount > 50000 ||
        (freeFrom !== null && (!Number.isFinite(freeFrom) || freeFrom < 0 || freeFrom > 50000)) ||
        !Number.isFinite(configuredDeliveryFee) || configuredDeliveryFee < 0 || configuredDeliveryFee > 50000 ||
        !Number.isFinite(subtotal) || Math.abs(Math.round(verifiedTotal * 100) / 100 - subtotal) >= 0.005 ||
        !Number.isFinite(deliveryFee) || deliveryFee < 0 || deliveryFee > 50000 || subtotal < minimumOrderAmount) {
      throw new HttpsError("failed-precondition", "El subtotal, el mínimo de compra o el envío no coinciden con la configuración del comercio.");
    }
    if (fulfillmentType === "RETIRO") {
      if (profileSnap.get("pickupEnabled") === false || deliveryFee !== 0) {
        throw new HttpsError("failed-precondition", "El comercio no habilitó el retiro o el cargo de envío es inválido.");
      }
    } else if (fulfillmentType === "DELIVERY") {
      if (profileSnap.get("deliveryEnabled") !== true || !String(order.deliveryAddress || "").trim()) {
        throw new HttpsError("failed-precondition", "El comercio no habilitó delivery o falta la dirección.");
      }
      const expectedFee = freeFrom !== null && subtotal >= freeFrom ? 0 : configuredDeliveryFee;
      if (Math.abs(expectedFee - deliveryFee) >= 0.005) {
        throw new HttpsError("failed-precondition", "El costo de envío cambió. Actualizá el pedido.");
      }
    } else {
      throw new HttpsError("invalid-argument", "La modalidad de entrega no es válida.");
    }
    if (Math.abs(Math.round((subtotal + deliveryFee) * 100) / 100 - amount) >= 0.005) {
      throw new HttpsError("failed-precondition", "El total no coincide con subtotal y envío.");
    }
    const customerBalance = Number(customerSnap.get("balance") ?? 0);
    const merchantBalance = Number(merchantSnap.get("balance") ?? 0);
    const customerCashbackTotal = Number(customerSnap.get("cashbackTotal") ?? 0);
    if (!Number.isFinite(customerBalance) || customerBalance < 0 ||
        !Number.isFinite(merchantBalance) || merchantBalance < 0 ||
        !Number.isFinite(customerCashbackTotal) || customerCashbackTotal < 0) {
      throw new HttpsError("failed-precondition", "Los saldos DEMO tienen datos inconsistentes. No se registró el pago.");
    }
    if (customerBalance < amount) throw new HttpsError("failed-precondition", "Saldo virtual insuficiente.");

    const policy = order.cashbackPolicy && typeof order.cashbackPolicy === "object"
      ? order.cashbackPolicy as Record<string, unknown> : {};
    const enabled = policy.enabled === true;
    const percentage = Number(policy.percentage || 0);
    if (enabled && (!Number.isFinite(percentage) || percentage <= 0 || percentage > 100)) {
      throw new HttpsError("failed-precondition", "Las condiciones de cashback guardadas en el pedido no son válidas.");
    }
    const capPerPurchase = policy.capPerPurchase == null ? null : Number(policy.capPerPurchase);
    const monthlyCap = policy.monthlyCap == null ? null : Number(policy.monthlyCap);
    if ((capPerPurchase !== null && (!Number.isFinite(capPerPurchase) || capPerPurchase <= 0)) ||
        (monthlyCap !== null && (!Number.isFinite(monthlyCap) || monthlyCap <= 0))) {
      throw new HttpsError("failed-precondition", "Los topes de cashback del pedido no son válidos.");
    }

    const dateParts = new Intl.DateTimeFormat("en", {
      timeZone: "America/Argentina/Buenos_Aires", year: "numeric", month: "2-digit"
    }).formatToParts(new Date());
    const year = dateParts.find((part) => part.type === "year")?.value || "";
    const month = dateParts.find((part) => part.type === "month")?.value || "";
    const period = `${year}-${month}`;
    const monthlyRef = db.collection("cashbackMonthly").doc(`${storeId}_${customerUid}_${period}`);
    const monthlySnap = await tx.get(monthlyRef);
    const alreadyEarned = Number(monthlySnap.get("earnedTotal") ?? 0);
    if (!Number.isFinite(alreadyEarned) || alreadyEarned < 0) {
      throw new HttpsError("data-loss", "El acumulado mensual de cashback no es válido. No se registró el pago.");
    }
    let cashbackAmount = enabled ? Math.round(subtotal * percentage) / 100 : 0;
    if (capPerPurchase !== null) cashbackAmount = Math.min(cashbackAmount, capPerPurchase);
    if (monthlyCap !== null) cashbackAmount = Math.min(cashbackAmount, Math.max(0, monthlyCap - alreadyEarned));
    cashbackAmount = Math.round(cashbackAmount * 100) / 100;

    const now = FieldValue.serverTimestamp();
    const cashbackData = {
      orderId, storeId, merchantUid, customerUid, period,
      percentage: enabled ? percentage : 0,
      orderAmount: subtotal, amount: cashbackAmount,
      capPerPurchase, monthlyCap,
      status: cashbackAmount > 0 ? "ACREDITADO · DEMO" : "SIN_CASHBACK",
      environment: "DEMO", createdAt: now
    };

    tx.update(customerRef, {
      balance: Math.round((customerBalance - amount + cashbackAmount) * 100) / 100,
      cashbackTotal: Math.round((customerCashbackTotal + cashbackAmount) * 100) / 100,
      lastOperationId: operationId, updatedAt: now
    });
    tx.update(merchantRef, {
      balance: Math.round((merchantBalance + amount) * 100) / 100,
      lastOperationId: operationId, updatedAt: now
    });
    tx.create(operationRef, {
      operationId, kind: "QR_PAYMENT", senderUid: customerUid,
      recipientUid: merchantUid, amount, orderId, storeId,
      subtotal, deliveryFee, fulfillmentType, paymentMethod: "OMNI_PAY_DEMO",
      cashbackAmount, createdAt: now, environment: "DEMO"
    });
    const storeName = String(order.storeName || "Comercio DEMO");
    const customerName = String(order.customerName || "Cliente OMNI");
    const ledgerContext = {
      orderId, storeId, subtotal, deliveryFee, cashbackAmount,
      paymentMethod: "OMNI_PAY_DEMO", fulfillmentType,
      environment: "DEMO", createdAt: now
    };
    tx.create(customerPaymentRef, {
      operationId, type: "COMPRA_LOCAL", title: `Compra en ${storeName}`,
      peerUid: merchantUid, peerName: storeName,
      amount, status: "APROBADA · DEMO", ...ledgerContext
    });
    tx.create(merchantPaymentRef, {
      operationId, type: "QR_COBRO", title: `Pedido de ${customerName}`,
      peerUid: customerUid, peerName: customerName,
      amount, status: "APROBADA · DEMO", ...ledgerContext
    });
    if (cashbackAmount > 0) {
      tx.create(customerRef.collection("transactions").doc(`cashback_${orderId}`), {
        operationId: `cashback_${orderId}`, type: "REINTEGRO_CASHBACK",
        title: `Cashback de ${storeName}`,
        peerUid: merchantUid, peerName: storeName,
        amount: cashbackAmount, orderId, storeId, orderAmount: subtotal,
        percentage, capPerPurchase, monthlyCap, period,
        status: "ACREDITADO · DEMO", environment: "DEMO", createdAt: now
      });
      tx.set(monthlyRef, {
        storeId, customerUid, period,
        earnedTotal: Math.round((alreadyEarned + cashbackAmount) * 100) / 100,
        updatedAt: now
      }, { merge: true });
    }
    tx.create(cashbackRef, cashbackData);
    tx.update(orderRef, {
      paymentStatus: "PAGADA · DEMO", paymentMethod: "OMNI_PAY_DEMO",
      cashbackAmount, updatedAt: now
    });

    return {
      operationId, status: "PEDIDO PAGADO · OMNI PAY DEMO",
      duplicate: false, cashbackAmount
    };
  });
});


function hashMerchantClaimCode(code: string): string {
  return createHash("sha256").update(code.trim().toUpperCase()).digest("hex");
}

/** Emite un código de un solo uso. Solo cuentas habilitadas en systemAdmins pueden emitirlo. */

/**
 * Cambia el estado de un pedido DEMO en servidor y deja una traza inmutable
 * de cada transición. Ningún cliente puede escribir directamente el documento.
 */
export const updateDemoStoreOrderStatus = onCall({ region: REGION }, async (request) => {
  const uid = requireUid(request);
  const orderId = String(request.data?.orderId || "").trim();
  const nextStatus = String(request.data?.nextStatus || "").trim();
  const rejectionReason = String(request.data?.rejectionReason || "").trim();
  if (!/^[A-Za-z0-9_-]{8,120}$/.test(orderId) ||
      !["ACEPTADO", "EN_PREPARACION", "LISTO", "EN_CAMINO", "ENTREGADO", "RECHAZADO", "CANCELADO"].includes(nextStatus)) {
    throw new HttpsError("invalid-argument", "El pedido o el estado solicitado no son válidos.");
  }
  if (nextStatus === "RECHAZADO" && (rejectionReason.length < 10 || rejectionReason.length > 240)) {
    throw new HttpsError("invalid-argument", "El motivo de rechazo debe tener entre 10 y 240 caracteres.");
  }
  const orderRef = db.collection("storeOrders").doc(orderId);
  return db.runTransaction(async (tx) => {
    const orderSnap = await tx.get(orderRef);
    if (!orderSnap.exists) throw new HttpsError("not-found", "El pedido ya no existe.");
    const order = orderSnap.data()!;
    if (order.environment !== "DEMO" || !order.storeId || !order.merchantUid || !order.customerUid) {
      throw new HttpsError("failed-precondition", "El pedido no pertenece a un comercio DEMO válido.");
    }
    const profileRef = db.collection("merchantProfiles").doc(String(order.storeId));
    const profileSnap = await tx.get(profileRef);
    if (!profileSnap.exists || profileSnap.get("environment") !== "DEMO" ||
        profileSnap.get("seedVersion") !== 1 || profileSnap.get("storeId") !== order.storeId ||
        profileSnap.get("ownerUid") !== order.merchantUid) {
      throw new HttpsError("failed-precondition", "La titularidad DEMO del comercio ya no está habilitada.");
    }

    const current = String(order.status || "");
    const isMerchant = uid === order.merchantUid;
    const isCustomer = uid === order.customerUid;
    let allowed = false;
    if (isMerchant) {
      allowed =
        (current === "RECIBIDO" && ["ACEPTADO", "RECHAZADO"].includes(nextStatus)) ||
        (current === "ACEPTADO" && ["EN_PREPARACION", "CANCELADO"].includes(nextStatus)) ||
        (current === "EN_PREPARACION" && ["LISTO", "CANCELADO"].includes(nextStatus)) ||
        (current === "LISTO" && (
          (nextStatus === "EN_CAMINO" && order.fulfillmentType === "DELIVERY") ||
          (nextStatus === "ENTREGADO" && order.fulfillmentType === "RETIRO")
        )) ||
        (current === "EN_CAMINO" && nextStatus === "ENTREGADO" && order.fulfillmentType === "DELIVERY");
      if (["RECHAZADO", "CANCELADO"].includes(nextStatus) && order.paymentStatus !== "PENDIENTE") {
        throw new HttpsError("failed-precondition", "No se puede rechazar ni cancelar un pedido pagado sin un circuito DEMO de devolución.");
      }
    } else if (isCustomer) {
      allowed = current === "RECIBIDO" && nextStatus === "CANCELADO" && order.paymentStatus === "PENDIENTE";
    } else {
      throw new HttpsError("permission-denied", "Solo el comprador o el titular del comercio pueden actualizar este pedido.");
    }
    if (!allowed) throw new HttpsError("failed-precondition", "La transición no está permitida para el estado actual o la modalidad del pedido.");

    const now = FieldValue.serverTimestamp();
    const historyRef = orderRef.collection("statusHistory").doc();
    const update = {
      status: nextStatus,
      updatedAt: now,
      ...(nextStatus === "RECHAZADO" ? { rejectionReason } : {})
    };
    tx.update(orderRef, update);
    tx.create(historyRef, {
      orderId, storeId: order.storeId, environment: "DEMO",
      actorUid: uid, actorRole: isMerchant ? "COMERCIO" : "COMPRADOR",
      fromStatus: current, toStatus: nextStatus,
      ...(nextStatus === "RECHAZADO" ? { reason: rejectionReason } : {}),
      createdAt: now
    });
    return { orderId, status: nextStatus, previousStatus: current, actorRole: isMerchant ? "COMERCIO" : "COMPRADOR" };
  });
});

/**
 * Reembolsa un pedido DEMO pagado. Solo el titular puede iniciar la devolución.
 * OMNI Pay revierte ambos saldos y el cashback en una sola transacción;
 * tarjeta DEMO solo revierte el registro simulado (nunca hubo movimiento de fondos).
 */
export const refundDemoStoreOrder = onCall({ region: REGION }, async (request) => {
  const uid = requireUid(request);
  const orderId = String(request.data?.orderId || "").trim();
  const reason = String(request.data?.reason || "").trim();
  if (!/^[A-Za-z0-9_-]{8,120}$/.test(orderId) || reason.length < 10 || reason.length > 240) {
    throw new HttpsError("invalid-argument", "Indicá un pedido válido y un motivo de 10 a 240 caracteres.");
  }
  const orderRef = db.collection("storeOrders").doc(orderId);
  const refundOperationId = `orderrefund_${orderId}`;
  const refundOperationRef = db.collection("walletOperations").doc(refundOperationId);
  return db.runTransaction(async (tx) => {
    const orderSnap = await tx.get(orderRef);
    if (!orderSnap.exists) throw new HttpsError("not-found", "El pedido ya no existe.");
    const order = orderSnap.data()!;
    if (order.environment !== "DEMO" || order.merchantUid !== uid || !order.customerUid || !order.storeId) {
      throw new HttpsError("permission-denied", "Solo el titular del comercio DEMO puede resolver esta devolución.");
    }
    if (order.paymentStatus === "REEMBOLSADA · DEMO" || order.status === "CANCELADO") {
      if (order.refundOperationId === refundOperationId) {
        const existingRefundOperation = await tx.get(refundOperationRef);
        if (!existingRefundOperation.exists || existingRefundOperation.get("orderId") !== orderId) {
          throw new HttpsError("data-loss", "La devolución figura cerrada, pero falta su operación contable.");
        }
        if (order.paymentMethod === "TARJETA_DEMO") {
          const cardPaymentId = String(order.cardPaymentId || "");
          const cardRef = cardPaymentId ? db.collection("demoCardPayments").doc(cardPaymentId) : null;
          const cardSnap = cardRef ? await tx.get(cardRef) : null;
          if (existingRefundOperation.get("kind") !== "CARD_DEMO_REVERSAL" ||
              Number(existingRefundOperation.get("amount")) !== 0 ||
              !cardSnap?.exists || cardSnap.get("status") !== "REVERSADA · DEMO" ||
              cardSnap.get("orderId") !== orderId) {
            throw new HttpsError("data-loss", "La reversión de tarjeta DEMO no coincide con su registro contable.");
          }
          return { orderId, refunded: true, duplicate: true, paymentMethod: "TARJETA_DEMO", walletMovement: false };
        }
        if (order.paymentMethod === "OMNI_PAY_DEMO") {
          const merchantRefundRef = accountRef(uid).collection("transactions").doc(refundOperationId);
          const customerRefundRef = accountRef(String(order.customerUid)).collection("transactions").doc(refundOperationId);
          const cashbackReversalRef = accountRef(String(order.customerUid)).collection("transactions").doc(`cashback_reversal_${orderId}`);
          const [merchantRefundSnap, customerRefundSnap, cashbackReversalSnap] = await Promise.all([
            tx.get(merchantRefundRef), tx.get(customerRefundRef), tx.get(cashbackReversalRef)
          ]);
          const originalAmount = Number(order.refundAmount);
          const cashbackReversed = Number(order.cashbackReversed ?? 0);
          if (existingRefundOperation.get("kind") !== "ORDER_REFUND" ||
              Number(existingRefundOperation.get("amount")) !== originalAmount ||
              !merchantRefundSnap.exists || !customerRefundSnap.exists ||
              Number(merchantRefundSnap.get("amount")) !== -originalAmount ||
              Number(customerRefundSnap.get("amount")) !== originalAmount - cashbackReversed ||
              (cashbackReversed > 0
                ? !cashbackReversalSnap.exists || Number(cashbackReversalSnap.get("amount")) !== -cashbackReversed
                : cashbackReversalSnap.exists)) {
            throw new HttpsError("data-loss", "La devolución tiene movimientos incompletos o inconsistentes; requiere revisión.");
          }
          return { orderId, refunded: true, duplicate: true, paymentMethod: "OMNI_PAY_DEMO",
            refundedAmount: originalAmount, cashbackReversed, walletMovement: true };
        }
        throw new HttpsError("data-loss", "El método de pago de la devolución no es válido.");
      }
      throw new HttpsError("failed-precondition", "El pedido ya está cerrado.");
    }
    if (order.paymentStatus !== "PAGADA · DEMO" ||
        !["RECIBIDO", "ACEPTADO", "EN_PREPARACION", "LISTO", "EN_CAMINO"].includes(String(order.status || ""))) {
      throw new HttpsError("failed-precondition", "Solo se pueden devolver pedidos pagados que todavía no fueron entregados.");
    }
    const profileRef = db.collection("merchantProfiles").doc(String(order.storeId));
    const merchantRef = accountRef(uid);
    const customerRef = accountRef(String(order.customerUid));
    const [profileSnap, merchantSnap, customerSnap, operationSnap] = await Promise.all([
      tx.get(profileRef), tx.get(merchantRef), tx.get(customerRef), tx.get(refundOperationRef)
    ]);
    if (!profileSnap.exists || profileSnap.get("environment") !== "DEMO" ||
        profileSnap.get("seedVersion") !== 1 || profileSnap.get("ownerUid") !== uid ||
        profileSnap.get("storeId") !== order.storeId) {
      throw new HttpsError("failed-precondition", "La titularidad DEMO del comercio no está vigente.");
    }
    if (operationSnap.exists) throw new HttpsError("data-loss", "Existe un registro de devolución previo; no se repetirán movimientos.");
    if (!merchantSnap.exists || !customerSnap.exists) {
      throw new HttpsError("failed-precondition", "No se encontraron ambas billeteras DEMO.");
    }
    const amount = Number(order.total);
    if (!Number.isFinite(amount) || amount <= 0 || amount > 50000) {
      throw new HttpsError("failed-precondition", "El importe original no es válido.");
    }
    const now = FieldValue.serverTimestamp();
    const historyRef = orderRef.collection("statusHistory").doc();
    if (order.paymentMethod === "TARJETA_DEMO") {
      const cardPaymentId = String(order.cardPaymentId || "");
      if (!cardPaymentId) throw new HttpsError("data-loss", "Falta el registro de la tarjeta DEMO; no se puede resolver automáticamente.");
      const cardRef = db.collection("demoCardPayments").doc(cardPaymentId);
      const cardSnap = await tx.get(cardRef);
      if (!cardSnap.exists || cardSnap.get("status") !== "APROBADA · DEMO" ||
          cardSnap.get("orderId") !== orderId || cardSnap.get("customerUid") !== order.customerUid) {
        throw new HttpsError("data-loss", "El registro de tarjeta DEMO no coincide con el pedido.");
      }
      tx.update(cardRef, { status: "REVERSADA · DEMO", refundReason: reason, refundedAt: now });
      tx.update(orderRef, {
        status: "CANCELADO", paymentStatus: "REEMBOLSADA · DEMO",
        refundOperationId: refundOperationId, refundReason: reason, refundedAt: now, updatedAt: now
      });
      tx.create(historyRef, {
        orderId, storeId: order.storeId, environment: "DEMO", actorUid: uid,
        actorRole: "COMERCIO", fromStatus: String(order.status), toStatus: "CANCELADO",
        reason, eventType: "REFUND", paymentMethod: "TARJETA_DEMO",
        note: "Se anuló el registro de pago simulado; no hubo movimiento de billeteras.",
        createdAt: now
      });
      tx.create(refundOperationRef, {
        operationId: refundOperationId, kind: "CARD_DEMO_REVERSAL",
        orderId, senderUid: uid, recipientUid: order.customerUid,
        amount: 0, originalAmount: amount, reason,
        environment: "DEMO", createdAt: now
      });
      return { orderId, refunded: true, duplicate: false, paymentMethod: "TARJETA_DEMO", walletMovement: false };
    }
    if (order.paymentMethod !== "OMNI_PAY_DEMO") {
      throw new HttpsError("failed-precondition", "El método de pago no admite devolución automática.");
    }
    const cashbackRef = db.collection("cashbackAccruals").doc(orderId);
    const [cashbackSnap] = await Promise.all([tx.get(cashbackRef)]);
    const cashbackAmount = cashbackSnap.exists ? Number(cashbackSnap.get("amount") || 0) : 0;
    if (!Number.isFinite(cashbackAmount) || cashbackAmount < 0 || cashbackAmount > amount) {
      throw new HttpsError("data-loss", "El cashback original no es válido; se requiere revisión manual.");
    }
    let monthlyRef: any = null;
    let monthlySnap: any = null;
    if (cashbackAmount > 0) {
      const period = String(cashbackSnap.get("period") || "");
      if (!/^\\d{4}-\\d{2}$/.test(period)) throw new HttpsError("data-loss", "El período del cashback no es válido.");
      monthlyRef = db.collection("cashbackMonthly").doc(`${order.storeId}_${order.customerUid}_${period}`);
      monthlySnap = await tx.get(monthlyRef);
    }
    const merchantBalance = Number(merchantSnap.get("balance") ?? 0);
    const customerBalance = Number(customerSnap.get("balance") ?? 0);
    const customerCashbackTotal = Number(customerSnap.get("cashbackTotal") ?? 0);
    if (!Number.isFinite(merchantBalance) || merchantBalance < 0 ||
        !Number.isFinite(customerBalance) || customerBalance < 0 ||
        !Number.isFinite(customerCashbackTotal) || customerCashbackTotal < 0) {
      throw new HttpsError("data-loss", "Los saldos DEMO tienen datos inconsistentes; no se ejecutó la devolución.");
    }
    if (merchantBalance < amount) {
      throw new HttpsError("failed-precondition", "El saldo del comercio no alcanza para devolver el importe completo. No se modificó ninguna cuenta.");
    }
    if (customerBalance < cashbackAmount || customerCashbackTotal < cashbackAmount) {
      throw new HttpsError("failed-precondition", "La billetera del comprador no tiene saldo suficiente para revertir el cashback acreditado. No se modificó ninguna cuenta.");
    }
    const monthlyEarned = cashbackAmount > 0 ? Number(monthlySnap?.get("earnedTotal") ?? 0) : 0;
    if (cashbackAmount > 0 && (!monthlySnap?.exists || !Number.isFinite(monthlyEarned) ||
        monthlyEarned < cashbackAmount)) {
      throw new HttpsError("data-loss", "El acumulado mensual de cashback no coincide; no se modificó ninguna cuenta.");
    }
    const merchantTxRef = merchantRef.collection("transactions").doc(refundOperationId);
    const customerTxRef = customerRef.collection("transactions").doc(refundOperationId);
    const cashbackReversalRef = customerRef.collection("transactions").doc(`cashback_reversal_${orderId}`);
    const [merchantTxSnap, customerTxSnap, cashbackReversalSnap] = await Promise.all([
      tx.get(merchantTxRef), tx.get(customerTxRef), tx.get(cashbackReversalRef)
    ]);
    if (merchantTxSnap.exists || customerTxSnap.exists || cashbackReversalSnap.exists) {
      throw new HttpsError("data-loss", "La devolución tiene movimientos parciales previos; se bloqueó para evitar duplicados.");
    }
    tx.update(merchantRef, { balance: Math.round((merchantBalance - amount) * 100) / 100, lastOperationId: refundOperationId, updatedAt: now });
    tx.update(customerRef, {
      balance: Math.round((customerBalance + amount - cashbackAmount) * 100) / 100,
      cashbackTotal: Math.round((customerCashbackTotal - cashbackAmount) * 100) / 100,
      lastOperationId: refundOperationId, updatedAt: now
    });
    tx.create(refundOperationRef, {
      operationId: refundOperationId, kind: "ORDER_REFUND",
      senderUid: uid, recipientUid: order.customerUid,
      amount, cashbackReversed: cashbackAmount, orderId, reason,
      environment: "DEMO", createdAt: now
    });
    const refundContext = {
      orderId, storeId: String(order.storeId), originalAmount: amount,
      cashbackReversed: cashbackAmount, paymentMethod: "OMNI_PAY_DEMO",
      fulfillmentType: String(order.fulfillmentType || "RETIRO"),
      reason, environment: "DEMO", createdAt: now
    };
    tx.create(merchantTxRef, {
      operationId: refundOperationId, type: "DEVOLUCION_PEDIDO",
      title: `Devolución a ${String(order.customerName || "Cliente OMNI")}`,
      peerUid: order.customerUid, peerName: String(order.customerName || "Cliente OMNI"),
      amount: -amount, status: "REEMBOLSADA · DEMO", ...refundContext
    });
    tx.create(customerTxRef, {
      operationId: refundOperationId, type: "DEVOLUCION_COMPRA",
      title: `Devolución de ${String(order.storeName || "comercio DEMO")}`,
      peerUid: uid, peerName: String(order.storeName || "Comercio DEMO"),
      amount: amount - cashbackAmount, status: "REEMBOLSADA · DEMO", ...refundContext
    });
    if (cashbackAmount > 0) {
      tx.create(cashbackReversalRef, {
        operationId: `cashback_reversal_${orderId}`, type: "REVERSO_CASHBACK",
        title: `Reversión de cashback · ${String(order.storeName || "comercio DEMO")}`,
        peerUid: uid, peerName: String(order.storeName || "Comercio DEMO"),
        amount: -cashbackAmount, orderId, storeId: String(order.storeId),
        originalAmount: amount, paymentMethod: "OMNI_PAY_DEMO",
        status: "REVERSADO · DEMO", environment: "DEMO", createdAt: now
      });
      tx.update(monthlyRef!, {
        earnedTotal: Math.round((monthlyEarned - cashbackAmount) * 100) / 100,
        updatedAt: now
      });
      tx.update(cashbackRef, { amount: 0, status: "REVERSADO · DEMO", reversedAmount: cashbackAmount, reversedAt: now });
    }
    tx.update(orderRef, {
      status: "CANCELADO", paymentStatus: "REEMBOLSADA · DEMO",
      refundOperationId: refundOperationId, refundReason: reason,
      refundAmount: amount, cashbackReversed: cashbackAmount, refundedAt: now, updatedAt: now
    });
    tx.create(historyRef, {
      orderId, storeId: order.storeId, environment: "DEMO", actorUid: uid,
      actorRole: "COMERCIO", fromStatus: String(order.status), toStatus: "CANCELADO",
      reason, eventType: "REFUND", paymentMethod: "OMNI_PAY_DEMO",
      refundedAmount: amount, cashbackReversed: cashbackAmount, createdAt: now
    });
    return { orderId, refunded: true, duplicate: false, paymentMethod: "OMNI_PAY_DEMO",
      refundedAmount: amount, cashbackReversed: cashbackAmount, walletMovement: true };
  });
});

export const issueDemoMerchantClaimCode = onCall({ region: REGION }, async (request) => {
  const uid = requireUid(request);
  const adminSnap = await db.collection("systemAdmins").doc(uid).get();
  if (!adminSnap.exists || adminSnap.get("enabled") !== true ||
      !["owner", "admin"].includes(String(adminSnap.get("role") || ""))) {
    throw new HttpsError("permission-denied", "Solo una cuenta administradora habilitada puede emitir códigos.");
  }
  const storeId = String(request.data?.storeId || "").trim();
  if (!/^[A-Za-z0-9_-]{3,100}$/.test(storeId)) {
    throw new HttpsError("invalid-argument", "Indicá un comercio DEMO válido.");
  }
  const profileRef = db.collection("merchantProfiles").doc(storeId);
  const code = `OMNI-${randomBytes(8).toString("hex").toUpperCase()}`;
  const codeHash = hashMerchantClaimCode(code);
  const codeRef = db.collection("demoMerchantClaimCodes").doc(codeHash);
  let storeName = "";
  await db.runTransaction(async (tx) => {
    const profile = await tx.get(profileRef);
    if (!profile.exists || profile.get("environment") !== "DEMO" ||
        Number(profile.get("seedVersion") || 0) !== 1) {
      throw new HttpsError("failed-precondition", "El código solo puede emitirse para un comercio del catálogo DEMO provisionado.");
    }
    if (String(profile.get("ownerUid") || "").trim().length > 0) {
      throw new HttpsError("already-exists", "El comercio DEMO ya tiene titular.");
    }
    storeName = String(profile.get("storeName") || "Comercio DEMO");
    const now = new Date();
    tx.create(codeRef, {
      storeId, active: true, createdByUid: uid,
      createdAt: FieldValue.serverTimestamp(),
      expiresAt: new Date(now.getTime() + 30 * 24 * 60 * 60 * 1000)
    });
  });
  return { code, storeId, storeName, expiresInDays: 30, singleUse: true };
});

/** Reclama un comercio DEMO usando un código secreto de un solo uso. */
export const claimDemoMerchant = onCall({ region: REGION }, async (request) => {
  const uid = requireUid(request);
  const code = String(request.data?.code || "").trim().toUpperCase();
  if (!/^OMNI-[A-F0-9]{16}$/.test(code)) {
    throw new HttpsError("invalid-argument", "El código de titularidad no tiene un formato válido.");
  }
  const codeHash = hashMerchantClaimCode(code);
  const codeRef = db.collection("demoMerchantClaimCodes").doc(codeHash);
  return db.runTransaction(async (tx) => {
    const codeSnap = await tx.get(codeRef);
    if (!codeSnap.exists || codeSnap.get("active") !== true) {
      throw new HttpsError("not-found", "El código no existe, ya fue utilizado o fue deshabilitado.");
    }
    const storeId = String(codeSnap.get("storeId") || "");
    const expectedStoreId = String(request.data?.storeId || "").trim();
    if (expectedStoreId && expectedStoreId !== storeId) {
      throw new HttpsError("failed-precondition", "El código pertenece a otro comercio. No se consumió.");
    }
    const profileRef = db.collection("merchantProfiles").doc(storeId);
    const profileSnap = await tx.get(profileRef);
    if (!profileSnap.exists || profileSnap.get("environment") !== "DEMO" ||
        Number(profileSnap.get("seedVersion") || 0) !== 1 ||
        String(profileSnap.get("ownerUid") || "").trim().length > 0) {
      throw new HttpsError("already-exists", "Este comercio ya tiene titular o no está habilitado como DEMO.");
    }
    const expiresAt = codeSnap.get("expiresAt")?.toDate?.();
    if (!expiresAt || expiresAt.getTime() <= Date.now()) {
      throw new HttpsError("deadline-exceeded", "El código de titularidad venció.");
    }
    const now = FieldValue.serverTimestamp();
    tx.update(profileRef, { ownerUid: uid, claimedAt: now, updatedAt: now });
    tx.update(codeRef, { active: false, claimedByUid: uid, claimedAt: now });
    return { storeId, storeName: String(profileSnap.get("storeName") || "Comercio DEMO"), claimed: true };
  });
});

type DemoSeedProduct = {
  id: string; name: string; category: string; price: number;
  originalPrice: number | null; inStock: boolean; unitLabel: string; description: string;
};
type DemoMerchantSeed = {
  id: string; name: string; type: string; sizeTier: string; capacityLabel: string;
  description: string; accentColorHex: number; hoursText: string; openTime: string;
  closeTime: string; products: DemoSeedProduct[];
};

const DEMO_MERCHANT_SEEDS: DemoMerchantSeed[] = [
  {
    id: "demo_kiosco_punto_24", name: "Punto 24 · Kiosco DEMO", type: "KIOSCO",
    sizeTier: "MICRO", capacityLabel: "Hasta 8 productos activos · retiro en local",
    description: "Comercio ficticio de escala micro para probar ventas rápidas, stock reducido y pedidos pequeños.",
    accentColorHex: 0xFFEF4444, hoursText: "Todos los días · 08:00 a 23:00", openTime: "08:00", closeTime: "23:00",
    products: [
      {id:"kp_agua_2l",name:"Agua mineral 2L",category:"BEBIDAS",price:1100,originalPrice:null,inStock:true,unitLabel:"bot.",description:"Agua sin gas · botella 2 litros"},
      {id:"kp_gaseosa_15",name:"Gaseosa cola 1.5L",category:"BEBIDAS",price:2450,originalPrice:2700,inStock:true,unitLabel:"bot.",description:"Bebida cola · botella 1.5 litros"},
      {id:"kp_galletas",name:"Galletitas surtidas 300g",category:"KIOSCO",price:1450,originalPrice:null,inStock:true,unitLabel:"paq.",description:"Paquete familiar"},
      {id:"kp_chocolate",name:"Chocolate con leche 90g",category:"KIOSCO",price:1750,originalPrice:null,inStock:true,unitLabel:"unid.",description:"Tableta individual"},
      {id:"kp_papas",name:"Papas fritas 100g",category:"KIOSCO",price:1650,originalPrice:null,inStock:true,unitLabel:"paq.",description:"Snack salado"},
      {id:"kp_alfajor",name:"Alfajor triple",category:"KIOSCO",price:1250,originalPrice:null,inStock:true,unitLabel:"unid.",description:"Alfajor de chocolate"},
      {id:"kp_yerba",name:"Yerba mate 500g",category:"ALMACEN",price:2350,originalPrice:null,inStock:true,unitLabel:"paq.",description:"Yerba tradicional"},
      {id:"kp_leche",name:"Leche larga vida 1L",category:"LACTEOS",price:1450,originalPrice:null,inStock:true,unitLabel:"litro",description:"Leche entera"}
    ]
  },
  {
    id: "demo_almacen_esquina", name: "Almacén La Esquina · DEMO", type: "ALMACEN",
    sizeTier: "PEQUENO", capacityLabel: "Hasta 15 productos activos · retiro y delivery",
    description: "Almacén barrial ficticio para probar catálogo cotidiano, reposición y entregas de cercanía.",
    accentColorHex: 0xFF10B981, hoursText: "Lun. a sáb. · 08:00 a 20:30", openTime: "08:00", closeTime: "20:30",
    products: [
      {id:"ae_leche",name:"Leche entera 1L",category:"LACTEOS",price:1450,originalPrice:null,inStock:true,unitLabel:"litro",description:"Leche larga vida"},
      {id:"ae_arroz",name:"Arroz largo fino 1kg",category:"ALMACEN",price:1850,originalPrice:null,inStock:true,unitLabel:"paq.",description:"Paquete de un kilo"},
      {id:"ae_fideos",name:"Fideos spaghetti 500g",category:"ALMACEN",price:1350,originalPrice:null,inStock:true,unitLabel:"paq.",description:"Pasta seca"},
      {id:"ae_yerba",name:"Yerba mate 1kg",category:"ALMACEN",price:4650,originalPrice:4950,inStock:true,unitLabel:"paq.",description:"Yerba con palo"},
      {id:"ae_azucar",name:"Azúcar 1kg",category:"ALMACEN",price:1250,originalPrice:null,inStock:true,unitLabel:"kg",description:"Azúcar blanca"},
      {id:"ae_aceite",name:"Aceite mezcla 900ml",category:"ALMACEN",price:2550,originalPrice:null,inStock:true,unitLabel:"bot.",description:"Aceite para cocinar"},
      {id:"ae_huevos",name:"Huevos x 12",category:"FRESCOS",price:3600,originalPrice:null,inStock:true,unitLabel:"doc.",description:"Maple de una docena"},
      {id:"ae_tomate",name:"Tomate perita 1kg",category:"FRESCOS",price:2100,originalPrice:null,inStock:true,unitLabel:"kg",description:"Producto fresco"},
      {id:"ae_gaseosa",name:"Gaseosa naranja 1.5L",category:"BEBIDAS",price:2250,originalPrice:null,inStock:true,unitLabel:"bot.",description:"Bebida sin alcohol"},
      {id:"ae_agua",name:"Agua mineral 2L",category:"BEBIDAS",price:1100,originalPrice:null,inStock:true,unitLabel:"bot.",description:"Agua sin gas"},
      {id:"ae_detergente",name:"Detergente 750ml",category:"LIMPIEZA",price:1950,originalPrice:null,inStock:true,unitLabel:"bot.",description:"Detergente para vajilla"},
      {id:"ae_lavandina",name:"Lavandina 1L",category:"LIMPIEZA",price:1350,originalPrice:null,inStock:true,unitLabel:"bot.",description:"Limpieza del hogar"},
      {id:"ae_pan",name:"Pan casero 1/2kg",category:"ALMACEN",price:1900,originalPrice:null,inStock:true,unitLabel:"unidad",description:"Pan del día"},
      {id:"ae_jabon",name:"Jabón de tocador x3",category:"LIMPIEZA",price:1850,originalPrice:null,inStock:true,unitLabel:"pack",description:"Pack de tres unidades"},
      {id:"ae_cafe",name:"Café molido 250g",category:"ALMACEN",price:4250,originalPrice:null,inStock:true,unitLabel:"paq.",description:"Café tostado molido"}
    ]
  },
  {
    id: "demo_panaderia_trigal", name: "Panadería Trigal · DEMO", type: "PANADERIA",
    sizeTier: "PEQUENO", capacityLabel: "Hasta 12 productos activos · retiro y delivery programado",
    description: "Panadería ficticia de producción diaria para probar disponibilidad, productos por unidad y combos.",
    accentColorHex: 0xFFF59E0B, hoursText: "Todos los días · 06:30 a 19:30", openTime: "06:30", closeTime: "19:30",
    products: [
      {id:"pt_flauta",name:"Pan francés 1kg",category:"ALMACEN",price:2600,originalPrice:null,inStock:true,unitLabel:"kg",description:"Pan del día"},
      {id:"pt_mixto",name:"Pan miñón 1/2kg",category:"ALMACEN",price:1450,originalPrice:null,inStock:true,unitLabel:"1/2 kg",description:"Pan fresco"},
      {id:"pt_medialunas",name:"Medialunas x 6",category:"ALMACEN",price:3900,originalPrice:4200,inStock:true,unitLabel:"pack",description:"Media docena"},
      {id:"pt_facturas",name:"Facturas surtidas x 6",category:"ALMACEN",price:3600,originalPrice:null,inStock:true,unitLabel:"pack",description:"Seis unidades"},
      {id:"pt_chipa",name:"Chipá x 250g",category:"ALMACEN",price:2800,originalPrice:null,inStock:true,unitLabel:"paq.",description:"Chipá horneado"},
      {id:"pt_torta",name:"Porción de pasta frola",category:"ALMACEN",price:1850,originalPrice:null,inStock:true,unitLabel:"porción",description:"Membrillo"},
      {id:"pt_cafe",name:"Café preparado",category:"BEBIDAS",price:1900,originalPrice:null,inStock:true,unitLabel:"vaso",description:"Café para llevar"},
      {id:"pt_leche",name:"Leche chocolatada 500ml",category:"LACTEOS",price:1750,originalPrice:null,inStock:true,unitLabel:"bot.",description:"Bebida láctea"},
      {id:"pt_pan_integral",name:"Pan integral 500g",category:"ALMACEN",price:2950,originalPrice:null,inStock:true,unitLabel:"unidad",description:"Pan con semillas"},
      {id:"pt_alfajor",name:"Alfajor artesanal",category:"KIOSCO",price:1600,originalPrice:null,inStock:true,unitLabel:"unid.",description:"Elaboración propia"},
      {id:"pt_prepizza",name:"Prepizza x 2",category:"ALMACEN",price:3200,originalPrice:null,inStock:true,unitLabel:"pack",description:"Dos bases listas"},
      {id:"pt_tostadas",name:"Tostadas 200g",category:"ALMACEN",price:2100,originalPrice:null,inStock:true,unitLabel:"paq.",description:"Tostadas clásicas"}
    ]
  },
  {
    id: "demo_supermercado_norte", name: "Supermercado Norte · DEMO", type: "SUPERMERCADO",
    sizeTier: "GRANDE", capacityLabel: "Hasta 31 productos activos · retiro y delivery",
    description: "Supermercado ficticio de mayor escala para probar variedad, stock amplio, múltiples categorías y pedidos grandes.",
    accentColorHex: 0xFF0D9488, hoursText: "Lun. a sáb. · 07:30 a 21:00", openTime: "07:30", closeTime: "21:00",
    products: [
      {id:"sn_leche",name:"Leche entera 1L",category:"LACTEOS",price:1450,originalPrice:null,inStock:true,unitLabel:"litro",description:"Leche larga vida"},
      {id:"sn_yogur",name:"Yogur bebible 1L",category:"LACTEOS",price:2100,originalPrice:null,inStock:true,unitLabel:"bot.",description:"Yogur frutilla"},
      {id:"sn_queso",name:"Queso cremoso 500g",category:"LACTEOS",price:4650,originalPrice:null,inStock:true,unitLabel:"trozo",description:"Queso fresco"},
      {id:"sn_manteca",name:"Manteca 200g",category:"LACTEOS",price:2450,originalPrice:null,inStock:true,unitLabel:"paq.",description:"Manteca clásica"},
      {id:"sn_arroz",name:"Arroz largo fino 1kg",category:"ALMACEN",price:1850,originalPrice:null,inStock:true,unitLabel:"paq.",description:"Arroz"},
      {id:"sn_fideos",name:"Fideos tirabuzón 500g",category:"ALMACEN",price:1350,originalPrice:null,inStock:true,unitLabel:"paq.",description:"Pasta seca"},
      {id:"sn_aceite",name:"Aceite girasol 900ml",category:"ALMACEN",price:2600,originalPrice:null,inStock:true,unitLabel:"bot.",description:"Aceite vegetal"},
      {id:"sn_yerba",name:"Yerba mate 1kg",category:"ALMACEN",price:4650,originalPrice:null,inStock:true,unitLabel:"paq.",description:"Yerba tradicional"},
      {id:"sn_cafe",name:"Café molido 250g",category:"ALMACEN",price:4250,originalPrice:null,inStock:true,unitLabel:"paq.",description:"Café"},
      {id:"sn_azucar",name:"Azúcar 1kg",category:"ALMACEN",price:1250,originalPrice:null,inStock:true,unitLabel:"kg",description:"Azúcar"},
      {id:"sn_huevos",name:"Huevos x 12",category:"FRESCOS",price:3600,originalPrice:null,inStock:true,unitLabel:"doc.",description:"Huevos frescos"},
      {id:"sn_tomate",name:"Tomates 1kg",category:"FRESCOS",price:2100,originalPrice:null,inStock:true,unitLabel:"kg",description:"Tomate fresco"},
      {id:"sn_manzana",name:"Manzanas 1kg",category:"FRESCOS",price:2800,originalPrice:null,inStock:true,unitLabel:"kg",description:"Fruta fresca"},
      {id:"sn_banana",name:"Bananas 1kg",category:"FRESCOS",price:2450,originalPrice:null,inStock:true,unitLabel:"kg",description:"Fruta fresca"},
      {id:"sn_gaseosa",name:"Gaseosa cola 1.5L",category:"BEBIDAS",price:2450,originalPrice:null,inStock:true,unitLabel:"bot.",description:"Bebida cola"},
      {id:"sn_agua",name:"Agua mineral 2L",category:"BEBIDAS",price:1100,originalPrice:null,inStock:true,unitLabel:"bot.",description:"Agua sin gas"},
      {id:"sn_jugo",name:"Jugo en polvo x 20g",category:"BEBIDAS",price:450,originalPrice:null,inStock:true,unitLabel:"sobre",description:"Bebida para preparar"},
      {id:"sn_detergente",name:"Detergente 750ml",category:"LIMPIEZA",price:1950,originalPrice:null,inStock:true,unitLabel:"bot.",description:"Limpieza"},
      {id:"sn_lavandina",name:"Lavandina 1L",category:"LIMPIEZA",price:1350,originalPrice:null,inStock:true,unitLabel:"bot.",description:"Limpieza"},
      {id:"sn_papel",name:"Papel higiénico x4",category:"LIMPIEZA",price:2800,originalPrice:null,inStock:true,unitLabel:"pack",description:"Higiene del hogar"},
      {id:"sn_jabon",name:"Jabón de tocador x3",category:"LIMPIEZA",price:1850,originalPrice:null,inStock:true,unitLabel:"pack",description:"Higiene personal"},
      {id:"sn_shampoo",name:"Shampoo 400ml",category:"LIMPIEZA",price:3950,originalPrice:null,inStock:true,unitLabel:"bot.",description:"Cuidado personal"},
      {id:"sn_pan",name:"Pan de campo 1kg",category:"ALMACEN",price:2600,originalPrice:null,inStock:true,unitLabel:"kg",description:"Pan fresco"},
      {id:"sn_galletas",name:"Galletitas dulces 300g",category:"KIOSCO",price:1450,originalPrice:null,inStock:true,unitLabel:"paq.",description:"Galletitas"},
      {id:"sn_chocolate",name:"Chocolate 90g",category:"KIOSCO",price:1750,originalPrice:null,inStock:true,unitLabel:"unid.",description:"Chocolate"},
      {id:"sn_cereales",name:"Cereales 300g",category:"ALMACEN",price:3200,originalPrice:null,inStock:true,unitLabel:"caja",description:"Cereales"},
      {id:"sn_pure",name:"Puré de tomate 520g",category:"ALMACEN",price:1250,originalPrice:null,inStock:true,unitLabel:"caja",description:"Conserva"},
      {id:"sn_atun",name:"Atún al natural 170g",category:"ALMACEN",price:2850,originalPrice:null,inStock:true,unitLabel:"lata",description:"Conserva"},
      {id:"sn_lentejas",name:"Lentejas 400g",category:"ALMACEN",price:1650,originalPrice:null,inStock:true,unitLabel:"paq.",description:"Legumbre seca"},
      {id:"sn_queso_rallado",name:"Queso rallado 40g",category:"LACTEOS",price:1350,originalPrice:null,inStock:true,unitLabel:"sobre",description:"Queso rallado"},
      {id:"sn_agua_sabor",name:"Agua saborizada 1.5L",category:"BEBIDAS",price:1950,originalPrice:null,inStock:true,unitLabel:"bot.",description:"Bebida sin alcohol"}
    ]
  },
  {
    id: "demo_tecnologia_pixel", name: "Pixel Lab · Tecnología DEMO", type: "SERVICIOS",
    sizeTier: "ESPECIALIZADO", capacityLabel: "Hasta 10 productos activos · retiro y envío coordinado",
    description: "Tienda ficticia especializada para probar productos tecnológicos, variantes y stock unitario.",
    accentColorHex: 0xFFB69CFF, hoursText: "Lun. a sáb. · 09:00 a 19:00", openTime: "09:00", closeTime: "19:00",
    products: [
      {id:"pl_cable",name:"Cable USB-C reforzado 1m",category:"ALMACEN",price:4500,originalPrice:null,inStock:true,unitLabel:"unid.",description:"Cable de carga y datos"},
      {id:"pl_cargador",name:"Cargador USB-C 25W",category:"ALMACEN",price:12500,originalPrice:13900,inStock:true,unitLabel:"unid.",description:"Cargador de pared"},
      {id:"pl_mouse",name:"Mouse inalámbrico",category:"ALMACEN",price:8900,originalPrice:null,inStock:true,unitLabel:"unid.",description:"Mouse óptico USB"},
      {id:"pl_auris",name:"Auriculares con micrófono",category:"ALMACEN",price:7200,originalPrice:null,inStock:true,unitLabel:"unid.",description:"Auriculares estéreo"},
      {id:"pl_pendrive",name:"Pendrive USB 64GB",category:"ALMACEN",price:9500,originalPrice:null,inStock:true,unitLabel:"unid.",description:"Memoria USB"},
      {id:"pl_funda",name:"Funda universal celular",category:"ALMACEN",price:5200,originalPrice:null,inStock:true,unitLabel:"unid.",description:"Funda flexible"},
      {id:"pl_vidrio",name:"Protector de pantalla",category:"ALMACEN",price:3900,originalPrice:null,inStock:true,unitLabel:"unid.",description:"Protector transparente"},
      {id:"pl_power",name:"Power bank 10.000mAh",category:"ALMACEN",price:18900,originalPrice:null,inStock:true,unitLabel:"unid.",description:"Batería portátil"},
      {id:"pl_parlante",name:"Parlante Bluetooth compacto",category:"ALMACEN",price:16500,originalPrice:null,inStock:true,unitLabel:"unid.",description:"Audio portátil"},
      {id:"pl_soporte",name:"Soporte de escritorio",category:"ALMACEN",price:6800,originalPrice:null,inStock:true,unitLabel:"unid.",description:"Soporte ajustable"}
    ]
  }
];

/**
 * Provisiona el catálogo inicial de comercios ficticios. No toca perfiles reales
 * ni sobrescribe comercios DEMO que ya tengan titular.
 */
export const provisionDemoMerchants = onCall({ region: REGION }, async (request) => {
  const uid = requireUid(request);
  const adminSnap = await db.collection("systemAdmins").doc(uid).get();
  if (!adminSnap.exists || adminSnap.get("enabled") !== true ||
      !["owner", "admin"].includes(String(adminSnap.get("role") || ""))) {
    throw new HttpsError("permission-denied", "Solo una cuenta administradora habilitada puede provisionar comercios DEMO.");
  }

  const results: Array<{storeId: string; name: string; result: string; products: number}> = [];
  for (const seed of DEMO_MERCHANT_SEEDS) {
    const profileRef = db.collection("merchantProfiles").doc(seed.id);
    const result = await db.runTransaction(async (tx) => {
      const existing = await tx.get(profileRef);
      if (existing.exists && existing.get("environment") !== "DEMO") {
        return "OMITIDO_PERFIL_NO_DEMO";
      }
      if (existing.exists && String(existing.get("ownerUid") || "").trim().length > 0) {
        return "OMITIDO_YA_RECLAMADO";
      }
      if (existing.exists && Number(existing.get("seedVersion") || 0) >= 1) {
        return "YA_PROVISIONADO";
      }
      const now = FieldValue.serverTimestamp();
      tx.set(profileRef, {
        storeId: seed.id,
        storeName: seed.name,
        type: seed.type,
        environment: "DEMO",
        ownerUid: "",
        sizeTier: seed.sizeTier,
        capacityLabel: seed.capacityLabel,
        description: seed.description,
        address: "Dirección ficticia · Entorno de prueba OMNI",
        phone: "",
        isOpen: true,
        hoursText: seed.hoursText,
        openTime: seed.openTime,
        closeTime: seed.closeTime,
        weekendHoursText: "Consultar disponibilidad DEMO",
        is24Hours: false,
        isOnDuty: false,
        openOnHolidays: false,
        pickupEnabled: true,
        deliveryEnabled: false,
        deliveryRadiusKm: 3,
        deliveryFee: 0,
        freeDeliveryFrom: null,
        minimumOrderAmount: 0,
        estimatedPrepMinutes: 20,
        scheduleNotes: "Datos ficticios para pruebas. No representa un comercio real.",
        rating: 0,
        reviewsCount: 0,
        distanceMeters: 0,
        accentColorHex: seed.accentColorHex,
        photosCount: 0,
        hasVideoTour: false,
        videoTourDuration: "",
        specialOfferText: "",
        minSpendDiscountThreshold: 0,
        minSpendDiscountPercent: 0,
        comboDeals: [],
        inventory: seed.products.map((product) => ({
          ...product, photoUri: null, barcode: ""
        })),
        news: [],
        cashback: { enabled: false, percentage: 0, capPerPurchase: null, monthlyCap: null },
        seedVersion: 1,
        seededByUid: uid,
        createdAt: existing.exists ? (existing.get("createdAt") || now) : now,
        updatedAt: now
      }, { merge: true });
      return "PROVISIONADO";
    });
    results.push({storeId: seed.id, name: seed.name, result, products: seed.products.length});
  }
  return {
    environment: "DEMO",
    total: results.length,
    provisioned: results.filter((item) => item.result === "PROVISIONADO").length,
    results
  };
});

export { omniAdminApi } from "./adminApi.js";
export { saveDemoMerchantCatalog } from "./demoMerchantCatalog.js";
