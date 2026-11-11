const PRIMARY_PROJECT = "omni-57f69";
const ADMIN_AUTH_PROJECT = "omni-admin-52e3f";
const FIRESTORE_ROOT = `https://firestore.googleapis.com/v1/projects/${PRIMARY_PROJECT}/databases/(default)/documents`;
const TOKEN_ISSUER = `https://securetoken.google.com/${ADMIN_AUTH_PROJECT}`;
let serviceTokenCache = { token: "", expiresAt: 0 };
let firebaseKeysCache = { keys: [], expiresAt: 0 };

function json(data, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" } });
}
function base64urlBytes(value) {
  const padded = value.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - value.length % 4) % 4);
  const raw = atob(padded);
  return Uint8Array.from(raw, c => c.charCodeAt(0));
}
function decodeJwtPart(value) {
  return JSON.parse(new TextDecoder().decode(base64urlBytes(value)));
}
function pemBytes(pem) {
  const body = pem.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, "");
  const raw = atob(body);
  return Uint8Array.from(raw, c => c.charCodeAt(0));
}
function encodeBase64Url(bytes) {
  let binary = "";
  for (const byte of new Uint8Array(bytes)) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}
async function verifyFirebaseToken(token, projectId = ADMIN_AUTH_PROJECT) {
  const parts = token.split(".");
  if (parts.length !== 3) throw new ApiError(401, "unauthenticated", "La sesión administrativa no es válida o venció. Ingresá nuevamente.");
  const header = decodeJwtPart(parts[0]);
  const claims = decodeJwtPart(parts[1]);
  const now = Math.floor(Date.now() / 1000);
  if (header.alg !== "RS256" || !header.kid || claims.aud !== projectId ||
      claims.iss !== `https://securetoken.google.com/${projectId}` || !claims.sub || claims.sub.length > 128 ||
      !Number.isFinite(claims.exp) || claims.exp <= now ||
      !Number.isFinite(claims.iat) || claims.iat > now + 300) {
    throw new ApiError(401, "unauthenticated", "La sesión administrativa no es válida o venció. Ingresá nuevamente.");
  }
  if (firebaseKeysCache.expiresAt < now || !firebaseKeysCache.keys.length) {
    const response = await fetch("https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com");
    if (!response.ok) throw new Error("No se pudieron obtener las claves de validación de Firebase.");
    const data = await response.json();
    firebaseKeysCache = { keys: data.keys || [], expiresAt: now + 3300 };
  }
  const jwk = firebaseKeysCache.keys.find(key => key.kid === header.kid);
  if (!jwk) throw new ApiError(401, "unauthenticated", "La firma de la sesión no es válida.");
  const publicKey = await crypto.subtle.importKey("jwk", jwk, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]);
  const valid = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", publicKey, base64urlBytes(parts[2]), new TextEncoder().encode(parts[0] + "." + parts[1]));
  if (!valid) throw new ApiError(401, "unauthenticated", "La firma de la sesión no es válida.");
  return { uid: claims.sub, email: String(claims.email || "") };
}
class ApiError extends Error {
  constructor(status, code, message) { super(message); this.status = status; this.code = code; }
}
async function googleAccessToken(env) {
  const now = Math.floor(Date.now() / 1000);
  if (serviceTokenCache.token && serviceTokenCache.expiresAt > now + 60) return serviceTokenCache.token;
  const account = JSON.parse(env.FIREBASE_SERVICE_ACCOUNT_JSON);
  if (!account.client_email || !account.private_key || account.project_id !== PRIMARY_PROJECT) throw new Error("La credencial de Firebase no corresponde al proyecto principal esperado.");
  const header = encodeBase64Url(new TextEncoder().encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const claims = encodeBase64Url(new TextEncoder().encode(JSON.stringify({
    iss: account.client_email, scope: "https://www.googleapis.com/auth/datastore",
    aud: "https://oauth2.googleapis.com/token", iat: now, exp: now + 3600
  })));
  const unsigned = header + "." + claims;
  const key = await crypto.subtle.importKey("pkcs8", pemBytes(account.private_key), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned));
  const assertion = unsigned + "." + encodeBase64Url(signature);
  const body = new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion });
  const response = await fetch("https://oauth2.googleapis.com/token", { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body });
  const data = await response.json();
  if (!response.ok || !data.access_token) throw new Error("No se pudo autenticar el servicio de OMNI con Google.");
  serviceTokenCache = { token: data.access_token, expiresAt: now + Number(data.expires_in || 3600) };
  return data.access_token;
}
function decodeValue(value) {
  if (!value || typeof value !== "object") return value;
  if ("nullValue" in value) return null;
  if ("stringValue" in value) return value.stringValue;
  if ("booleanValue" in value) return value.booleanValue;
  if ("integerValue" in value) return Number(value.integerValue);
  if ("doubleValue" in value) return Number(value.doubleValue);
  if ("timestampValue" in value) return value.timestampValue;
  if ("referenceValue" in value) return value.referenceValue;
  if ("geoPointValue" in value) return value.geoPointValue;
  if ("arrayValue" in value) return (value.arrayValue.values || []).map(decodeValue);
  if ("mapValue" in value) return decodeFields(value.mapValue.fields || {});
  return value;
}
function decodeFields(fields) {
  return Object.fromEntries(Object.entries(fields || {}).map(([key, value]) => [key, decodeValue(value)]));
}
function encodeValue(value) {
  if (value === null || value === undefined) return { nullValue: "NULL_VALUE" };
  if (typeof value === "string") return { stringValue: value };
  if (typeof value === "boolean") return { booleanValue: value };
  if (typeof value === "number") return Number.isInteger(value) ? { integerValue: String(value) } : { doubleValue: value };
  if (Array.isArray(value)) return { arrayValue: { values: value.map(encodeValue) } };
  if (typeof value === "object") return { mapValue: { fields: encodeFields(value) } };
  return { stringValue: String(value) };
}
function encodeFields(data) {
  return Object.fromEntries(Object.entries(data).map(([key, value]) => {
    if (typeof value === "string" && /(?:At|_at)$/.test(key) && Number.isFinite(Date.parse(value))) {
      return [key, { timestampValue: new Date(value).toISOString() }];
    }
    return [key, encodeValue(value)];
  }));
}
function documentData(doc) {
  return { id: doc.name.split("/").pop(), ...decodeFields(doc.fields || {}) };
}
async function firestore(env, path, options = {}) {
  const token = await googleAccessToken(env);
  const response = await fetch(FIRESTORE_ROOT + path, {
    ...options,
    headers: { Authorization: "Bearer " + token, "Content-Type": "application/json", ...(options.headers || {}) }
  });
  const raw = await response.text();
  let data = {};
  try { data = raw ? JSON.parse(raw) : {}; } catch {}
  if (!response.ok) {
    const message = data.error?.message || "Error al consultar la base de datos OMNI.";
    if (response.status === 404) return { missing: true };
    throw new Error(message);
  }
  return data;
}
async function getDocument(env, collection, id) {
  return firestore(env, "/" + encodeURIComponent(collection) + "/" + encodeURIComponent(id));
}
async function listDocumentsPage(env, collection, limit = 100, pageToken = "") {
  const pageSize = Math.min(Math.max(Number(limit) || 100, 1), 200);
  const params = new URLSearchParams({ pageSize: String(pageSize) });
  if (pageToken) params.set("pageToken", pageToken);
  const data = await firestore(env, "/" + encodeURIComponent(collection) + "?" + params.toString());
  return { items: (data.documents || []).map(documentData), nextPageToken: data.nextPageToken || "" };
}
async function recentDocuments(env, collection, limit = 8, offset = 0) {
  const structuredQuery = {
    from: [{ collectionId: collection }],
    orderBy: [{ field: { fieldPath: "createdAt" }, direction: "DESCENDING" }],
    limit: Math.min(Math.max(Number(limit) || 8, 1), 5000)
  };
  if (Number(offset) > 0) structuredQuery.offset = Math.min(Number(offset), 50000);
  const rows = await firestore(env, ":runQuery", { method: "POST", body: JSON.stringify({ structuredQuery }) });
  return (rows || []).filter(row => row.document).map(row => documentData(row.document));
}
async function queryRecentDocuments(env, collection, orderField, limit) {
  const structuredQuery = {
    from: [{ collectionId: collection }],
    orderBy: [{ field: { fieldPath: orderField }, direction: "DESCENDING" }],
    limit: Math.min(Math.max(Number(limit) || 100, 1), 1000)
  };
  const rows = await firestore(env, ":runQuery", { method: "POST", body: JSON.stringify({ structuredQuery }) });
  return (rows || []).filter(row => row.document).map(row => documentData(row.document));
}
async function usageEventsLast7Days(env) {
  const cutoff = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000).toISOString();
  const structuredQuery = {
    from: [{ collectionId: "usageEvents" }],
    where: { fieldFilter: { field: { fieldPath: "createdAt" }, op: "GREATER_THAN_OR_EQUAL", value: { timestampValue: cutoff } } },
    orderBy: [{ field: { fieldPath: "createdAt" }, direction: "DESCENDING" }],
    limit: 5000
  };
  const rows = await firestore(env, ":runQuery", { method: "POST", body: JSON.stringify({ structuredQuery }) });
  return (rows || []).filter(row => row.document).map(row => documentData(row.document));
}
async function countDocuments(env, collection) {
  const query = {
    structuredAggregationQuery: {
      structuredQuery: { from: [{ collectionId: collection }] },
      aggregations: [{ count: { } , alias: "total" }]
    }
  };
  const result = await firestore(env, ":runAggregationQuery", { method: "POST", body: JSON.stringify(query) });
  return Number(result[0]?.result?.aggregateFields?.total?.integerValue || 0);
}
async function writeDocument(env, collection, id, data, merge = true) {
  const mask = Object.keys(data).map(key => "updateMask.fieldPaths=" + encodeURIComponent(key)).join("&");
  return firestore(env, "/" + encodeURIComponent(collection) + "/" + encodeURIComponent(id) + (merge && mask ? "?" + mask : ""), {
    method: "PATCH", body: JSON.stringify({ fields: encodeFields(data) })
  });
}
function userLabels(profiles) {
  return new Map(profiles.map(profile => [
    String(profile.uid || profile.id || ""),
    String(profile.displayName || profile.fullName || profile.name || profile.email || "Cuenta OMNI · nombre no informado")
  ]).filter(([uid]) => uid));
}
function attachUserLabels(events, profiles) {
  const labels = userLabels(profiles);
  return events.map(event => ({ ...event, userLabel: labels.get(String(event.uid || "")) || "Cuenta OMNI · nombre no informado" }));
}
function aggregateGeoPresence(records, profiles = []) {
  const labels = userLabels(profiles);
  const now = Date.now();
  const points = [];
  const visibleCells = new Set();
  for (const record of records) {
    if (record.consent !== true) continue;
    const updatedAt = Date.parse(record.updatedAt || "");
    // En línea = OMNI publicó una posición autorizada en los últimos tres minutos.
    if (!Number.isFinite(updatedAt) || now - updatedAt > 7 * 60 * 1000 || updatedAt > now + 60 * 1000) continue;
    const uid = String(record.uid || record.id || "");
    const latitude = Number(record.latitude);
    const longitude = Number(record.longitude);
    if (!uid || !Number.isFinite(latitude) || !Number.isFinite(longitude) ||
        Math.abs(latitude) > 90 || Math.abs(longitude) > 180) continue;
    // La app comparte únicamente una celda aproximada de ~1 km; no aceptar GPS exacto.
    if (Math.abs(latitude * 100 - Math.round(latitude * 100)) > 0.00001 ||
        Math.abs(longitude * 100 - Math.round(longitude * 100)) > 0.00001) continue;
    const accuracyBand = ["hasta 100 m", "100 a 500 m", "500 m a 1 km", "más de 1 km"].includes(String(record.accuracyBand || ""))
      ? String(record.accuracyBand) : "más de 1 km";
    const locality = String(record.locality || "Localidad no informada").trim().slice(0, 80);
    const province = String(record.province || "Provincia no informada").trim().slice(0, 80);
    const country = String(record.country || "País no informado").trim().slice(0, 80);
    visibleCells.add(latitude.toFixed(2) + "," + longitude.toFixed(2));
    points.push({
      uid,
      userLabel: labels.get(uid) || ("Cuenta OMNI · " + uid.slice(0, 8)),
      latitude,
      longitude,
      accuracyBand,
      locality,
      province,
      country,
      updatedAt: record.updatedAt,
      status: "online"
    });
  }
  return {
    points,
    freshUsers: points.length,
    activeUsers: points.length,
    visibleAreas: visibleCells.size,
    privacyThreshold: 1,
    freshnessMinutes: 7,
    freshnessHours: 0.05
  };
}

function summarizeUsage(events) {
  const meaningful = events.filter(event => event.event !== "DIAGNOSTIC_TEST");
  const activeUids = new Set(meaningful.map(event => String(event.uid || "")).filter(Boolean));
  const sessionGroups = new Map();
  const hourlyActivity = Array.from({ length: 24 }, (_, hour) => ({ hour, count: 0 }));
  const weekdayActivity = Array.from({ length: 7 }, (_, day) => ({
    day, label: ["Domingo", "Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado"][day], count: 0
  }));
  const sessionDurations = [];
  const screenDurations = [];
  for (const event of meaningful) {
    const sessionId = String(event.sessionId || "");
    if (sessionId) {
      if (!sessionGroups.has(sessionId)) sessionGroups.set(sessionId, []);
      sessionGroups.get(sessionId).push(event);
    }
    const hour = Number(event.localHour);
    if (Number.isInteger(hour) && hour >= 0 && hour <= 23) hourlyActivity[hour].count += 1;
    const weekday = Number(event.localDayOfWeek);
    if (Number.isInteger(weekday) && weekday >= 1 && weekday <= 7) weekdayActivity[weekday - 1].count += 1;
    const data = event.data && typeof event.data === "object" ? event.data : {};
    const screenSeconds = Number(data.screenDurationSeconds ?? event.screenDurationSeconds ?? 0);
    if (event.event === "SCREEN_EXIT" && Number.isFinite(screenSeconds) && screenSeconds > 0) screenDurations.push(screenSeconds);
  }
  let totalSessionMs = 0;
  let completedSessions = 0;
  let totalScreenMs = 0;
  let measuredScreens = screenDurations.length;
  const screenViews7d = meaningful.filter(event => event.event === "SCREEN_VIEW").length;
  const actions7d = meaningful.filter(event => ![
    "SESSION_START", "SESSION_END", "APP_FOREGROUND", "APP_BACKGROUND", "SCREEN_VIEW", "SCREEN_EXIT"
  ].includes(event.event)).length;
  const topScreens = new Map();
  const moduleUsage = new Map();
  const eventUsage = new Map();
  for (const event of meaningful) {
    if (event.event === "SCREEN_VIEW") {
      const screen = String(event.screen || event.module || "PANTALLA_NO_INFORMADA");
      topScreens.set(screen, (topScreens.get(screen) || 0) + 1);
    } else if (!["SESSION_START", "SESSION_END", "APP_FOREGROUND", "APP_BACKGROUND", "SCREEN_EXIT"].includes(event.event)) {
      const module = String(event.module || "MODULO_NO_INFORMADO");
      const eventName = String(event.event || "TIPO_DE_EVENTO_NO_INFORMADO");
      moduleUsage.set(module, (moduleUsage.get(module) || 0) + 1);
      eventUsage.set(eventName, (eventUsage.get(eventName) || 0) + 1);
    }
  }
  for (const group of sessionGroups.values()) {
    group.sort((a, b) => Date.parse(a.createdAt || 0) - Date.parse(b.createdAt || 0));
    const sessionStart = group.find(event => event.event === "SESSION_START");
    const finish = [...group].reverse().find(event => event.event === "SESSION_END");
    if (sessionStart && finish) {
      const finishData = finish.data && typeof finish.data === "object" ? finish.data : {};
      const measuredSeconds = Number(finishData.sessionDurationSeconds ?? finish.sessionDurationSeconds ?? 0);
      const duration = Number.isFinite(measuredSeconds) && measuredSeconds > 0
        ? measuredSeconds * 1000
        : Date.parse(finish.createdAt) - Date.parse(sessionStart.createdAt);
      if (Number.isFinite(duration) && duration > 0) {
        totalSessionMs += duration;
        completedSessions += 1;
      }
    }
    if (!screenDurations.length) {
      for (let i = 0; i < group.length; i++) {
        const event = group[i];
        if (event.event !== "SCREEN_VIEW") continue;
        const next = group.slice(i + 1).find(item =>
          item.event === "SCREEN_EXIT" || item.event === "SCREEN_VIEW" ||
          item.event === "APP_BACKGROUND" || item.event === "SESSION_END"
        );
        if (!next) continue;
        const duration = Date.parse(next.createdAt) - Date.parse(event.createdAt);
        if (Number.isFinite(duration) && duration > 0 && duration <= 6 * 60 * 60 * 1000) {
          totalScreenMs += duration;
          measuredScreens += 1;
        }
      }
    }
  }
  if (screenDurations.length) totalScreenMs = screenDurations.reduce((sum, seconds) => sum + seconds * 1000, 0);
  const observedHours = hourlyActivity.filter(item => item.count > 0);
  const peakActivityHour = observedHours.length
    ? observedHours.reduce((best, item) => item.count > best.count ? item : best)
    : null;
  const quietestActivityHour = observedHours.length
    ? observedHours.reduce((best, item) => item.count < best.count ? item : best)
    : null;
  return {
    activeUsers7d: activeUids.size,
    sessions7d: new Set(meaningful.filter(event => event.event === "SESSION_START").map(event => event.sessionId).filter(Boolean)).size,
    screenViews7d,
    actions7d,
    averageSessionSeconds: completedSessions ? Math.round(totalSessionMs / completedSessions / 1000) : null,
    averageScreenSeconds: measuredScreens ? Math.round(totalScreenMs / measuredScreens / 1000) : null,
    measuredSessions: completedSessions,
    measuredScreens,
    hourlyActivity,
    weekdayActivity,
    peakActivityHour,
    quietestActivityHour,
    topScreens: [...topScreens.entries()].map(([name, count]) => ({ name, count })).sort((a, b) => b.count - a.count).slice(0, 8),
    moduleUsage: [...moduleUsage.entries()].map(([name, count]) => ({ name, count })).sort((a, b) => b.count - a.count).slice(0, 12),
    eventBreakdown: [...eventUsage.entries()].map(([name, count]) => ({ name, count })).sort((a, b) => b.count - a.count).slice(0, 16),
    featureMetrics: {
      aiCameraScans: moduleUsage.get("ai_camera") || 0,
      mapsInteractions: moduleUsage.get("maps_locations") || 0,
      commerceShares: [...eventUsage.entries()].filter(([name]) => name.includes("SHARED") || name.includes("SHARE")).reduce((sum, [, count]) => sum + count, 0),
      jobInteractions: moduleUsage.get("jobs") || moduleUsage.get("conex") || 0,
      tourismInteractions: moduleUsage.get("tourism") || 0,
      routineWeatherInteractions: moduleUsage.get("routine_weather") || 0,
      eventInteractions: (moduleUsage.get("events") || 0) + (moduleUsage.get("community") || 0),
      walletUsageActions: (moduleUsage.get("wallet") || 0) + (moduleUsage.get("wallet_qr") || 0)
    }
  };
}
async function dashboard(env) {
  const collections = ["users", "walletAccounts", "walletOperations", "merchantProfiles", "storeOrders", "omniEvents", "omniMoments", "jobPosts", "omniProfessionalProfiles", "appFeedback", "usageEvents", "geoPresence", "moduleRegistry", "adminAuditLogs"];
  const counts = await Promise.all(collections.map(name => countDocuments(env, name)));
  const [walletPage, operations, userProfiles, usageEvents, geoPresencePage] = await Promise.all([
    listDocumentsPage(env, "walletAccounts", 200),
    recentDocuments(env, "walletOperations", 8),
    recentDocuments(env, "users", 200),
    usageEventsLast7Days(env),
    listAllDocuments(env, "geoPresence", 10000)
  ]);
  const wallets = walletPage.items;
  const resolvedCounts = Object.fromEntries(collections.map((name, index) => [
    name, name === "users" ? Math.max(counts[index], userProfiles.length) : counts[index]
  ]));
  const recentUsers = [...userProfiles]
    .sort((a, b) => String(b.createdAt || b.lastSeenAt || b.updatedAt || "").localeCompare(String(a.createdAt || a.lastSeenAt || a.updatedAt || "")))
    .slice(0, 8);
  const now = new Date();
  const dayKeys = Array.from({ length: 7 }, (_, index) => {
    const date = new Date(now);
    date.setUTCDate(date.getUTCDate() - (6 - index));
    return date.toISOString().slice(0, 10);
  });
  const usageByDay = Object.fromEntries(dayKeys.map(day => [day, 0]));
  for (const event of usageEvents) {
    if (event.event === "DIAGNOSTIC_TEST") continue;
    const day = String(event.createdAt || "").slice(0, 10);
    if (Object.prototype.hasOwnProperty.call(usageByDay, day)) usageByDay[day] += 1;
  }
  const usageTrend = dayKeys.map(day => ({
    date: day.slice(8, 10) + "/" + day.slice(5, 7),
    count: usageByDay[day]
  }));
  const analytics = summarizeUsage(usageEvents);
  const geoActivity = aggregateGeoPresence(geoPresencePage.items, userProfiles);
  return {
    counts: resolvedCounts,
    activeUsers7d: analytics.activeUsers7d,
    analytics,
    usageTrend,
    walletBalanceSample: wallets.reduce((sum, item) => sum + (Number(item.balance) || 0), 0),
    walletSampleSize: wallets.length,
    usageSampleLimit: 5000,
    usageSampleComplete: usageEvents.length < 5000,
    recentUsers, recentOperations: operations,
    geoActivity: geoActivity.points,
    geoActivitySummary: {
      consentedUsersFresh24h: geoActivity.freshUsers,
      visibleAreas: geoActivity.visibleAreas,
      privacyThreshold: geoActivity.privacyThreshold,
      freshnessHours: geoActivity.freshnessHours,
      sampleComplete: geoPresencePage.complete
    },
    recentUsageEvents: attachUserLabels(usageEvents.filter(event => event.event !== "DIAGNOSTIC_TEST").slice(0, 8), userProfiles),
    diagnostics: {
      usageEventsVisibleLast7Days: usageEvents.length,
      diagnosticEventSeen: usageEvents.some(event => event.event === "DIAGNOSTIC_TEST"),
      latestUsageEventAt: usageEvents[0]?.createdAt || null,
      userProfilesVisible: userProfiles.length,
      usageQueryWindowDays: 7
    }
  };
}
async function listAllDocuments(env, collection, maxItems = 1000) {
  const items = [];
  let pageToken = "";
  do {
    const page = await listDocumentsPage(env, collection, Math.min(200, maxItems - items.length), pageToken);
    items.push(...page.items);
    pageToken = page.nextPageToken;
  } while (pageToken && items.length < maxItems);
  return { items, complete: !pageToken };
}
async function countSubcollectionDocuments(env, collectionId) {
  const query = {
    structuredAggregationQuery: {
      structuredQuery: { from: [{ collectionId, allDescendants: true }] },
      aggregations: [{ count: {}, alias: "total" }]
    }
  };
  const result = await firestore(env, ":runAggregationQuery", { method: "POST", body: JSON.stringify(query) });
  return Number(result[0]?.result?.aggregateFields?.total?.integerValue || 0);
}
async function recentTransactionDocuments(env, limit = 100) {
  const structuredQuery = {
    from: [{ collectionId: "transactions", allDescendants: true }],
    orderBy: [{ field: { fieldPath: "createdAt" }, direction: "DESCENDING" }],
    limit: Math.min(Math.max(Number(limit) || 100, 1), 500)
  };
  const rows = await firestore(env, ":runQuery", { method: "POST", body: JSON.stringify({ structuredQuery }) });
  return (rows || []).filter(row => row.document).map(row => documentData(row.document));
}
async function finance(env) {
  const [accountCount, accountPage, operationCount, operationPage, transactionCount, transactions, userPage] = await Promise.all([
    countDocuments(env, "walletAccounts"),
    listAllDocuments(env, "walletAccounts", 1000),
    countDocuments(env, "walletOperations"),
    recentDocuments(env, "walletOperations", 5000),
    countSubcollectionDocuments(env, "transactions"),
    recentTransactionDocuments(env, 100),
    listAllDocuments(env, "users", 1000)
  ]);
  const profileByUid = new Map(userPage.items.map(profile => [
    String(profile.uid || profile.id || ""),
    String(profile.displayName || profile.fullName || profile.name || profile.email || "Titular no informado")
  ]));
  const accounts = accountPage.items.slice(0, 100).map(account => ({
    ...account,
    holderName: profileByUid.get(String(account.uid || account.id || "")) || "Titular no informado"
  }));
  const operations = operationPage.slice(0, 100);
  const currentBalance = accountPage.items.reduce((sum, account) => sum + (Number(account.balance) || 0), 0);
  const initialCapital = accountCount * 50000;
  const deposits = operationPage.filter(item => item.kind === "DEPOSIT").reduce((sum, item) => sum + (Number(item.amount) || 0), 0);
  const transferVolume = operationPage.filter(item => item.kind === "TRANSFER").reduce((sum, item) => sum + (Number(item.amount) || 0), 0);
  const qrVolume = operationPage.filter(item => item.kind === "QR_PAYMENT").reduce((sum, item) => sum + (Number(item.amount) || 0), 0);
  const transferIn = operationPage.filter(item => item.kind === "TRANSFER" && item.recipientUid).reduce((sum, item) => sum + (Number(item.amount) || 0), 0);
  const qrCollections = operationPage.filter(item => item.kind === "QR_PAYMENT" && item.recipientUid).reduce((sum, item) => sum + (Number(item.amount) || 0), 0);
  return {
    summary: {
      currentBalance,
      initialCapital,
      deposits,
      transferVolume,
      qrVolume,
      transferIn,
      qrCollections,
      totalCredits: initialCapital + deposits,
      outflows: transferVolume + qrVolume,
      accountCount,
      operationCount,
      transactionCount,
      transactionSampleSize: transactions.length,
      transactionSampleComplete: transactionCount <= transactions.length,
      accountSampleSize: accountPage.items.length,
      accountSampleComplete: accountPage.complete,
      operationsSampleSize: operationPage.length,
      operationsSampleComplete: operationCount <= operationPage.length
    },
    accounts,
    transactions,
    operations
  };
}

async function handleAction(env, action, data) {
  switch (action) {
    case "dashboard": return dashboard(env);
    case "geoActivity": {
      const [records, profiles] = await Promise.all([
        queryRecentDocuments(env, "geoPresence", "updatedAt", 500),
        recentDocuments(env, "users", 500)
      ]);
      const geo = aggregateGeoPresence(records, profiles);
      return {
        geoActivity: geo.points,
        geoActivitySummary: {
          activeUsers: geo.activeUsers,
          visibleMarkers: geo.points.length,
          visibleAreas: geo.visibleAreas,
          freshnessMinutes: geo.freshnessMinutes,
          coordinatePrecision: "celda aproximada de 1 km",
          sampleComplete: records.length < 500,
          sourceAvailable: true
        }
      };
    }
    case "finance": return finance(env);
    case "list": {
      const collection = String(data.collection || "");
      const allowed = new Set(["users", "walletAccounts", "walletOperations", "merchantProfiles", "storeOrders", "omniEvents", "omniMoments", "jobPosts", "omniProfessionalProfiles", "appFeedback", "usageEvents", "moduleRegistry", "adminAuditLogs", "betaInvitations"]);
      if (!allowed.has(collection)) throw new ApiError(400, "invalid-argument", "Colección no disponible.");
      if (collection === "usageEvents") {
        const offset = Math.max(0, Math.min(Number(data.offset) || 0, 50000));
        const pageSize = Math.min(Math.max(Number(data.limit) || 100, 1), 100);
        const [items, profiles] = await Promise.all([
          recentDocuments(env, collection, pageSize + 1, offset),
          recentDocuments(env, "users", 200)
        ]);
        const hasMore = items.length > pageSize;
        return { items: attachUserLabels(items.slice(0, pageSize), profiles), hasMore, nextOffset: hasMore ? offset + pageSize : null };
      }
      const page = await listDocumentsPage(env, collection, data.limit, String(data.pageToken || ""));
      let items = page.items;
      if (collection === "walletAccounts") {
        const profiles = await listAllDocuments(env, "users", 1000);
        const labels = new Map(profiles.items.map(profile => [
          String(profile.uid || profile.id || ""),
          String(profile.displayName || profile.fullName || profile.name || profile.email || "Titular no informado")
        ]));
        items = items.map(item => ({
          ...item,
          holderName: labels.get(String(item.uid || item.id || "")) || "Titular no informado"
        }));
      }
      items = items.map(item => {
        if (collection === "omniMoments") {
          return {
            id: item.id, ownerUid: item.ownerUid, occasion: item.occasion,
            date: item.date, status: item.status, createdAt: item.createdAt,
            updatedAt: item.updatedAt
          };
        }
        if (collection === "omniProfessionalProfiles") {
          return {
            id: item.id, ownerUid: item.ownerUid, profession: item.profession,
            location: item.location, availability: item.availability,
            visibility: item.visibility, updatedAt: item.updatedAt
          };
        }
        return item;
      });
      return { items, nextPageToken: page.nextPageToken, hasMore: Boolean(page.nextPageToken) };
    }
    case "getConfig": {
      const snap = await getDocument(env, "adminConfig", "global");
      return { config: snap.missing ? {} : decodeFields(snap.fields || {}) };
    }
    default: throw new ApiError(400, "invalid-argument", "Acción administrativa desconocida.");
  }
}
async function handleGeoPresenceRequest(request, env) {
  if (request.method !== "POST") return json({ ok: false, error: "method-not-allowed", message: "Usá POST." }, 405);
  const authHeader = request.headers.get("Authorization") || "";
  if (!authHeader.startsWith("Bearer ")) throw new ApiError(401, "unauthenticated", "Falta la sesión de OMNI.");
  const identity = await verifyFirebaseToken(authHeader.slice(7).trim(), PRIMARY_PROJECT);
  const data = await request.json();
  if (data.enabled === false) {
    await firestore(env, "/geoPresence/" + encodeURIComponent(identity.uid), { method: "DELETE" });
    return json({ ok: true, result: { deleted: true } });
  }
  if (data.enabled !== true || data.consent !== true) throw new ApiError(400, "invalid-argument", "La ubicación requiere autorización expresa.");
  const latitude = Number(data.latitude);
  const longitude = Number(data.longitude);
  const roundedLatitude = Math.round(latitude * 100) / 100;
  const roundedLongitude = Math.round(longitude * 100) / 100;
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude) ||
      Math.abs(latitude) > 90 || Math.abs(longitude) > 180 ||
      Math.abs(latitude - roundedLatitude) > 0.000001 ||
      Math.abs(longitude - roundedLongitude) > 0.000001) {
    throw new ApiError(400, "invalid-argument", "La ubicación debe estar redondeada a una celda aproximada de 1 km.");
  }
  const allowedAccuracy = ["hasta 100 m", "100 a 500 m", "500 m a 1 km", "más de 1 km"];
  const accuracyBand = allowedAccuracy.includes(String(data.accuracyBand || "")) ? String(data.accuracyBand) : "más de 1 km";
  await writeDocument(env, "geoPresence", identity.uid, {
    uid: identity.uid,
    latitude: roundedLatitude,
    longitude: roundedLongitude,
    locality: String(data.locality || "").trim().slice(0, 80) || "Localidad no informada",
    province: String(data.province || "").trim().slice(0, 80) || "Provincia no informada",
    country: String(data.country || "").trim().slice(0, 80) || "País no informado",
    accuracyBand,
    consent: true,
    updatedAt: new Date().toISOString()
  });
  return json({ ok: true, result: { saved: true, precision: "approx-1km" } });
}

export default {
  async fetch(request, env) {
    const requestUrl = new URL(request.url);
    if (requestUrl.pathname === "/geo-presence") {
      try { return await handleGeoPresenceRequest(request, env); }
      catch (error) {
        if (error instanceof ApiError) return json({ ok: false, error: error.code, message: error.message }, error.status);
        console.error("OMNI geo-presence API error", error);
        return json({ ok: false, error: "internal", message: "No se pudo actualizar la presencia geográfica." }, 500);
      }
    }
    if (request.method !== "POST") return json({ ok: false, error: "method-not-allowed", message: "Usá POST." }, 405);
    try {
      const authHeader = request.headers.get("Authorization") || "";
      if (!authHeader.startsWith("Bearer ")) throw new ApiError(401, "unauthenticated", "Falta la sesión administrativa.");
      const identity = await verifyFirebaseToken(authHeader.slice(7).trim());
      const admin = await getDocument(env, "systemAdmins", identity.uid);
      if (admin.missing) throw new ApiError(403, "permission-denied", "Esta cuenta no está habilitada como administradora de OMNI.");
      const fields = decodeFields(admin.fields || {});
      const role = fields.role;
      if (fields.enabled !== true || !["owner", "admin"].includes(role)) throw new ApiError(403, "permission-denied", "Esta cuenta no está habilitada como administradora de OMNI.");
      const body = await request.json();
      const result = await handleAction(env, String(body.action || ""), body.data && typeof body.data === "object" ? body.data : {});
      return json({ ok: true, result });
    } catch (error) {
      if (error instanceof ApiError) return json({ ok: false, error: error.code, message: error.message }, error.status);
      console.error("OMNI Admin API error", error);
      return json({ ok: false, error: "internal", message: "No se pudo completar la operación." }, 500);
    }
  }
};
