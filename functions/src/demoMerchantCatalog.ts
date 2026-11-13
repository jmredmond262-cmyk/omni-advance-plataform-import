import { getApps, initializeApp } from "firebase-admin/app";
import { FieldValue, getFirestore } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";

if (getApps().length === 0) initializeApp();
const db = getFirestore();
const REGION = "us-central1";

type DataMap = Record<string, unknown>;

function requireUid(request: { auth?: { uid: string } | null }): string {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Iniciá sesión para administrar tu comercio DEMO.");
  return uid;
}

function asList(value: unknown, label: string, max: number): DataMap[] {
  if (!Array.isArray(value) || value.length > max || value.some((item) => !item || typeof item !== "object" || Array.isArray(item))) {
    throw new HttpsError("invalid-argument", `${label}: formato o cantidad no válida.`);
  }
  return value as DataMap[];
}

function boundedString(value: unknown, min: number, max: number, label: string, optional = false): string {
  if (optional && (value === null || value === undefined)) return "";
  if (typeof value !== "string" || value.trim().length < min || value.length > max) {
    throw new HttpsError("invalid-argument", `${label}: longitud no válida.`);
  }
  return value;
}

function validPrice(value: unknown, max: number, label: string): number {
  const price = Number(value);
  if (typeof value !== "number" || !Number.isFinite(price) || price <= 0 || price > max) {
    throw new HttpsError("invalid-argument", `${label}: importe no válido.`);
  }
  return price;
}

function onlyKeys(item: DataMap, allowed: string[], label: string) {
  if (Object.keys(item).some((key) => !allowed.includes(key))) {
    throw new HttpsError("invalid-argument", `${label}: contiene campos no permitidos.`);
  }
}

function validateProducts(raw: unknown, limit: number): DataMap[] {
  const products = asList(raw, "Productos", limit);
  const ids = new Set<string>();
  return products.map((product, index) => {
    const label = `Producto ${index + 1}`;
    onlyKeys(product, ["id", "name", "category", "price", "originalPrice", "inStock", "stockQuantity", "unitLabel", "photoUri", "barcode", "description"], label);
    const id = boundedString(product.id, 1, 100, `${label} · ID`).trim();
    if (ids.has(id)) throw new HttpsError("invalid-argument", "Los identificadores de producto deben ser únicos.");
    ids.add(id);
    boundedString(product.name, 2, 80, `${label} · nombre`);
    const price = validPrice(product.price, 50000, `${label} · precio`);
    if (product.originalPrice !== null && product.originalPrice !== undefined) {
      const original = validPrice(product.originalPrice, 100000, `${label} · precio anterior`);
      if (original <= price) throw new HttpsError("invalid-argument", `${label}: el precio anterior debe superar al actual.`);
    }
    const category = boundedString(product.category, 1, 40, `${label} · categoría`);
    if (!/^[A-Z_]+$/.test(category)) throw new HttpsError("invalid-argument", `${label}: categoría no válida.`);
    if (typeof product.inStock !== "boolean") throw new HttpsError("invalid-argument", `${label}: disponibilidad no válida.`);
    const stockQuantity = Number(product.stockQuantity);
    if (!Number.isInteger(stockQuantity) || stockQuantity < 0 || stockQuantity > 100000 || product.inStock !== (stockQuantity > 0)) {
      throw new HttpsError("invalid-argument", `${label}: el stock debe ser un entero entre 0 y 100.000 y coincidir con su disponibilidad.`);
    }
    boundedString(product.unitLabel, 1, 24, `${label} · unidad`);
    boundedString(product.photoUri, 0, 1000, `${label} · foto`, true);
    boundedString(product.barcode, 0, 64, `${label} · código de barras`, true);
    boundedString(product.description, 0, 500, `${label} · descripción`);
    return product;
  });
}

function validateCombos(raw: unknown): DataMap[] {
  const combos = asList(raw, "Combos", 30);
  return combos.map((combo, index) => {
    const label = `Combo ${index + 1}`;
    onlyKeys(combo, ["title", "description", "discountBadge", "audience"], label);
    boundedString(combo.title, 2, 80, `${label} · título`);
    boundedString(combo.description, 0, 300, `${label} · descripción`);
    boundedString(combo.discountBadge, 0, 24, `${label} · etiqueta`);
    boundedString(combo.audience, 0, 24, `${label} · público`);
    return combo;
  });
}

function validateNews(raw: unknown, storeId: string, storeName: string): DataMap[] {
  const news = asList(raw, "Novedades", 50);
  const ids = new Set<string>();
  return news.map((item, index) => {
    const label = `Novedad ${index + 1}`;
    onlyKeys(item, ["id", "storeId", "storeName", "title", "content", "timeAgoText", "badge", "isHot"], label);
    const id = boundedString(item.id, 1, 100, `${label} · ID`).trim();
    if (ids.has(id)) throw new HttpsError("invalid-argument", "Los identificadores de novedades deben ser únicos.");
    ids.add(id);
    if (item.storeId !== storeId || item.storeName !== storeName) {
      throw new HttpsError("invalid-argument", `${label}: no corresponde a este comercio.`);
    }
    boundedString(item.title, 2, 100, `${label} · título`);
    boundedString(item.content, 0, 1000, `${label} · contenido`);
    boundedString(item.timeAgoText, 0, 40, `${label} · fecha`);
    boundedString(item.badge, 0, 40, `${label} · etiqueta`);
    if (typeof item.isHot !== "boolean") throw new HttpsError("invalid-argument", `${label}: indicador no válido.`);
    return item;
  });
}

export const saveDemoMerchantCatalog = onCall({ region: REGION }, async (request) => {
  const uid = requireUid(request);
  const storeId = typeof request.data?.storeId === "string" ? request.data.storeId.trim() : "";
  if (!/^[a-zA-Z0-9_-]{3,120}$/.test(storeId)) {
    throw new HttpsError("invalid-argument", "El identificador del comercio no es válido.");
  }

  const profileRef = db.collection("merchantProfiles").doc(storeId);
  return db.runTransaction(async (tx) => {
    const profile = await tx.get(profileRef);
    if (!profile.exists || profile.get("environment") !== "DEMO" ||
        profile.get("seedVersion") !== 1 || profile.get("ownerUid") !== uid ||
        profile.get("storeId") !== storeId) {
      throw new HttpsError("permission-denied", "No sos el titular habilitado de este comercio DEMO.");
    }

    const tier = String(profile.get("sizeTier") || "");
    const limits: Record<string, number> = {
      MICRO: 20, PEQUENO: 50, MEDIANO: 100, GRANDE: 250, ESPECIALIZADO: 50
    };
    const limit = limits[tier];
    if (!limit) throw new HttpsError("failed-precondition", "El comercio no tiene un tamaño DEMO válido.");
    const storeName = String(profile.get("storeName") || "");
    const inventory = validateProducts(request.data?.inventory, limit);
    const previousInventory = Array.isArray(profile.get("inventory")) ? profile.get("inventory") as DataMap[] : [];
    const previousById = new Map(previousInventory.map((item) => [String(item.id || ""), item]));
    const inventoryMovements = inventory.flatMap((product) => {
      const productId = String(product.id);
      const previous = previousById.get(productId);
      const before = Number.isInteger(Number(previous?.stockQuantity)) ? Number(previous?.stockQuantity) : 0;
      const after = Number(product.stockQuantity);
      if (before === after) return [];
      const movementRef = profileRef.collection("inventoryMovements").doc(`adjust_${Date.now()}_${productId}_${Math.random().toString(36).slice(2, 8)}`);
      return [{ movementRef, data: {
        movementId: movementRef.id, storeId, productId, productName: String(product.name),
        type: previous ? "AJUSTE_TITULAR" : "ALTA_INVENTARIO",
        quantityDelta: after - before, stockBefore: before, stockAfter: after,
        actorUid: uid, environment: "DEMO", createdAt: FieldValue.serverTimestamp()
      }}];
    });
    const comboDeals = validateCombos(request.data?.comboDeals);
    const news = validateNews(request.data?.news, storeId, storeName);

    for (const movement of inventoryMovements) tx.create(movement.movementRef, movement.data);
    tx.update(profileRef, {
      inventory, comboDeals, news,
      catalogUpdatedByUid: uid,
      catalogUpdatedAt: FieldValue.serverTimestamp(),
      updatedAt: FieldValue.serverTimestamp()
    });
    return { saved: true, storeId, products: inventory.length, combos: comboDeals.length, news: news.length };
  });
});
