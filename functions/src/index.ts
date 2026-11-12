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

    if (operationSnap.exists || customerPaymentSnap.exists) {
      const existingMerchantPayment = await tx.get(merchantPaymentRef);
      const existingCashback = await tx.get(cashbackRef);
      const previous = operationSnap.data()!;
      if (previous.orderId !== orderId || previous.senderUid !== customerUid ||
          previous.recipientUid !== merchantUid || previous.kind !== "QR_PAYMENT" ||
          !customerPaymentSnap.exists || !existingMerchantPayment.exists ||
          !existingCashback.exists || order.paymentStatus !== "PAGADA · DEMO") {
        throw new HttpsError("data-loss", "El pedido tiene un registro de pago incompleto. No se volverá a debitar.");
      }
      return {
        operationId, status: "PEDIDO YA PAGADO · DEMO",
        duplicate: true, cashbackAmount: Number(existingCashback.get("amount") || 0)
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
        profileSnap.get("environment") !== "DEMO") {
      throw new HttpsError("failed-precondition", "La billetera o titularidad del comercio no está disponible.");
    }

    const amount = Number(order.total);
    if (!Number.isFinite(amount) || amount <= 0 || amount > 50000) {
      throw new HttpsError("failed-precondition", "El total del pedido no es válido.");
    }
    const customerBalance = Number(customerSnap.get("balance") || 0);
    const merchantBalance = Number(merchantSnap.get("balance") || 0);
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
    const alreadyEarned = Number(monthlySnap.get("earnedTotal") || 0);
    let cashbackAmount = enabled ? Math.round(amount * percentage) / 100 : 0;
    if (capPerPurchase !== null) cashbackAmount = Math.min(cashbackAmount, capPerPurchase);
    if (monthlyCap !== null) cashbackAmount = Math.min(cashbackAmount, Math.max(0, monthlyCap - alreadyEarned));
    cashbackAmount = Math.round(cashbackAmount * 100) / 100;

    const now = FieldValue.serverTimestamp();
    const cashbackData = {
      orderId, storeId, merchantUid, customerUid, period,
      percentage: enabled ? percentage : 0,
      orderAmount: amount, amount: cashbackAmount,
      capPerPurchase, monthlyCap,
      status: cashbackAmount > 0 ? "ACREDITADO · DEMO" : "SIN_CASHBACK",
      environment: "DEMO", createdAt: now
    };

    tx.update(customerRef, {
      balance: Math.round((customerBalance - amount + cashbackAmount) * 100) / 100,
      cashbackTotal: Math.round((Number(customerSnap.get("cashbackTotal") || 0) + cashbackAmount) * 100) / 100,
      lastOperationId: operationId, updatedAt: now
    });
    tx.update(merchantRef, {
      balance: Math.round((merchantBalance + amount) * 100) / 100,
      lastOperationId: operationId, updatedAt: now
    });
    tx.create(operationRef, {
      operationId, kind: "QR_PAYMENT", senderUid: customerUid,
      recipientUid: merchantUid, amount, orderId,
      cashbackAmount, createdAt: now, environment: "DEMO"
    });
    tx.create(customerPaymentRef, {
      operationId, type: "COMPRA_LOCAL", title: "Compra en comercio OMNI",
      peerUid: merchantUid, peerName: String(order.storeName || "Comercio DEMO"),
      amount, status: "APROBADA · DEMO", environment: "DEMO", createdAt: now
    });
    tx.create(merchantPaymentRef, {
      operationId, type: "QR_COBRO", title: "Cobro de pedido OMNI",
      peerUid: customerUid, peerName: String(order.customerName || "Cliente OMNI"),
      amount, status: "APROBADA · DEMO", environment: "DEMO", createdAt: now
    });
    if (cashbackAmount > 0) {
      tx.create(customerRef.collection("transactions").doc(`cashback_${orderId}`), {
        operationId: `cashback_${orderId}`, type: "REINTEGRO_CASHBACK",
        title: `Cashback de ${String(order.storeName || "comercio OMNI")}`,
        peerUid: merchantUid, peerName: String(order.storeName || "Comercio DEMO"),
        amount: cashbackAmount, status: "ACREDITADO · DEMO",
        environment: "DEMO", createdAt: now
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
export const issueDemoMerchantClaimCode = onCall({ region: REGION }, async (request) => {
  const uid = requireUid(request);
  const adminSnap = await db.collection("systemAdmins").doc(uid).get();
  if (!adminSnap.exists || adminSnap.get("enabled") !== true ||
      !["owner", "admin"].includes(String(adminSnap.get("role") || ""))) {
    throw new HttpsError("permission-denied", "Solo una cuenta administradora habilitada puede emitir códigos.");
  }
  const storeId = String(request.data?.storeId || "").trim();
  const storeName = String(request.data?.storeName || "").trim().slice(0, 100);
  if (!/^[A-Za-z0-9_-]{3,100}$/.test(storeId) || !storeName) {
    throw new HttpsError("invalid-argument", "Indicá un comercio DEMO válido.");
  }
  const profileRef = db.collection("merchantProfiles").doc(storeId);
  const code = `OMNI-${randomBytes(5).toString("hex").toUpperCase()}`;
  const codeHash = hashMerchantClaimCode(code);
  const codeRef = db.collection("demoMerchantClaimCodes").doc(codeHash);
  await db.runTransaction(async (tx) => {
    const profile = await tx.get(profileRef);
    if (profile.exists && profile.get("environment") !== "DEMO") {
      throw new HttpsError("failed-precondition", "El código solo puede emitirse para un comercio DEMO.");
    }
    if (profile.exists && String(profile.get("ownerUid") || "").trim().length > 0) {
      throw new HttpsError("already-exists", "El comercio DEMO ya tiene titular.");
    }
    const now = new Date();
    tx.set(profileRef, {
      storeId, storeName, environment: "DEMO",
      ownerUid: "", cashback: {
        enabled: false, percentage: 0, capPerPurchase: null, monthlyCap: null
      },
      ...(profile.exists ? {} : { createdAt: FieldValue.serverTimestamp() }),
      updatedAt: FieldValue.serverTimestamp()
    }, { merge: true });
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
  if (!/^OMNI-[A-F0-9]{10}$/.test(code)) {
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

export { omniAdminApi } from "./adminApi.js";
