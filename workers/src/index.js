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
// Live map feed: individual GPS positions only while the account has explicitly enabled sharing.
function aggregateGeoPresence(records, profiles) {
  const labels = userLabels(profiles);
  const now = Date.now();
  const points = [];
  let activeUsers = 0;
  for (const record of records) {
    if (record.consent !== true) continue;
    const updatedAt = Date.parse(record.updatedAt || "");
    if (!Number.isFinite(updatedAt) || now - updatedAt > 3 * 60 * 1000 || updatedAt > now + 60 * 1000) continue;
    const uid = String(record.uid || record.id || "");
    const latitude = Number(record.latitude);
    const longitude = Number(record.longitude);
    const accuracyMeters = Number(record.accuracyMeters);
    if (!uid || !Number.isFinite(latitude) || !Number.isFinite(longitude) ||
        Math.abs(latitude) > 90 || Math.abs(longitude) > 180 ||
        !Number.isFinite(accuracyMeters) || accuracyMeters <= 0 || accuracyMeters > 10000) continue;
    const locationTime = Number(record.locationTimeMillis || Date.parse(record.locationTime || record.updatedAt || ""));
    if (!Number.isFinite(locationTime) || now - locationTime > 5 * 60 * 1000 || locationTime > now + 60 * 1000) continue;
    activeUsers += 1;
    points.push({
      uid,
      userLabel: labels.get(uid) || ("Cuenta OMNI · " + uid.slice(0, 8)),
      latitude,
      longitude,
      accuracyMeters: Math.round(accuracyMeters),
      locality: String(record.locality || "Localidad no informada").trim().slice(0, 80),
      province: String(record.province || "Provincia no informada").trim().slice(0, 80),
      country: String(record.country || "País no informado").trim().slice(0, 80),
      updatedAt: record.updatedAt,
      locationTime: record.locationTime || record.updatedAt,
      status: "online",
      source: "GPS_ANDROID"
    });
  }
  return { points, activeUsers, freshnessMinutes: 3 };
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
  const collections = ["users", "walletAccounts", "walletOperations", "merchantProfiles", "storeOrders", "storeReceipts", "storeReceiptAdjustments", "omniEvents", "omniMoments", "jobPosts", "omniProfessionalProfiles", "appFeedback", "usageEvents", "geoPresence", "moduleRegistry", "adminAuditLogs"];
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
      activeUsers: geoActivity.activeUsers,
      visibleMarkers: geoActivity.points.length,
      freshnessMinutes: geoActivity.freshnessMinutes,
      coordinatePrecision: "GPS individual con consentimiento explícito",
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
  const [accountCount, accountPage, operationCount, operationPage, transactionCount, transactions, userPage, storeOrderCount, storeOrdersPage] = await Promise.all([
    countDocuments(env, "walletAccounts"),
    listAllDocuments(env, "walletAccounts", 1000),
    countDocuments(env, "walletOperations"),
    recentDocuments(env, "walletOperations", 5000),
    countSubcollectionDocuments(env, "transactions"),
    recentTransactionDocuments(env, 100),
    listAllDocuments(env, "users", 1000),
    countDocuments(env, "storeOrders"),
    recentDocuments(env, "storeOrders", 5000)
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
  const paidOrders = storeOrdersPage.filter(order =>
    ["PAGADA · DEMO", "PAGADA · EFECTIVO_DEMO"].includes(String(order.paymentStatus || "")));
  const refundedOrders = storeOrdersPage.filter(order =>
    String(order.paymentStatus || "") === "REEMBOLSADA · DEMO");
  const unpaidCancelledOrders = storeOrdersPage.filter(order =>
    String(order.status || "") === "CANCELADO" && !refundedOrders.includes(order));
  const sumOrderTotals = rows => rows.reduce((sum, order) => {
    const amount = Number(order.total);
    return sum + (Number.isFinite(amount) && amount > 0 ? amount : 0);
  }, 0);
  const paidByMethod = method => paidOrders
    .filter(order => String(order.paymentMethod || "") === method)
    .reduce((sum, order) => sum + (Number(order.total) || 0), 0);
  const commerce = {
    orderCount: storeOrderCount,
    sampledOrders: storeOrdersPage.length,
    sampleComplete: storeOrderCount <= storeOrdersPage.length,
    paidOrderCount: paidOrders.length,
    refundedOrderCount: refundedOrders.length,
    cancelledUnpaidCount: unpaidCancelledOrders.length,
    paidGross: sumOrderTotals(paidOrders),
    refundedGross: sumOrderTotals(refundedOrders),
    omniPayDemoGross: paidByMethod("OMNI_PAY_DEMO"),
    cardDemoGross: paidByMethod("TARJETA_DEMO"),
    cashDemoGross: paidByMethod("EFECTIVO_DEMO")
  };
  return {
    commerce,
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

function randomHex(byteCount) {
  const bytes = new Uint8Array(byteCount);
  crypto.getRandomValues(bytes);
  return [...bytes].map(value => value.toString(16).padStart(2, "0")).join("").toUpperCase();
}
async function sha256Hex(value) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return [...new Uint8Array(digest)].map(value => value.toString(16).padStart(2, "0")).join("");
}
async function issueDemoMerchantCode(env, uid, storeId) {
  if (!/^[A-Za-z0-9_-]{3,100}$/.test(storeId)) throw new ApiError(400, "invalid-argument", "Seleccioná un comercio DEMO válido.");
  const transactionResult = await firestore(env, ":beginTransaction", { method: "POST", body: JSON.stringify({ options: { readWrite: {} } }) });
  const transaction = transactionResult.transaction;
  if (!transaction) throw new Error("No se pudo iniciar la transacción de titularidad.");
  const profileName = `${FIRESTORE_ROOT}/merchantProfiles/${encodeURIComponent(storeId)}`;
  const batch = await firestore(env, ":batchGet", { method: "POST", body: JSON.stringify({ documents: [profileName], transaction }) });
  const profile = Array.isArray(batch) ? batch.find(item => item.found)?.found : null;
  if (!profile) throw new ApiError(404, "not-found", "El comercio DEMO no existe.");
  const data = decodeFields(profile.fields || {});
  if (data.environment !== "DEMO" || Number(data.seedVersion || 0) !== 1) throw new ApiError(409, "failed-precondition", "Solo se pueden asignar comercios del catálogo DEMO provisionado.");
  if (String(data.ownerUid || "").trim()) throw new ApiError(409, "already-exists", "Este comercio DEMO ya tiene titular.");
  const code = "OMNI-" + randomHex(8);
  const codeHash = await sha256Hex(code);
  const now = new Date();
  const expiresAt = new Date(now.getTime() + 30 * 24 * 60 * 60 * 1000);
  const codeName = `${FIRESTORE_ROOT}/demoMerchantClaimCodes/${codeHash}`;
  await firestore(env, ":commit", {
    method: "POST",
    body: JSON.stringify({
      transaction,
      writes: [{
        update: { name: codeName, fields: encodeFields({ storeId, active: true, createdByUid: uid, createdAt: now.toISOString(), expiresAt: expiresAt.toISOString() }) },
        currentDocument: { exists: false }
      }]
    })
  });
  return { code, storeId, storeName: String(data.storeName || "Comercio DEMO"), expiresInDays: 30, singleUse: true };
}
async function demoMerchants(env) {
  const snapshot = await listAllDocuments(env, "merchantProfiles", 5000);
  const items = snapshot.items.filter(item => item.environment === "DEMO" && Number(item.seedVersion || 0) === 1).map(item => ({
    id: item.id, storeName: String(item.storeName || "Comercio DEMO"), sizeTier: String(item.sizeTier || ""),
    capacityLabel: String(item.capacityLabel || ""), claimed: String(item.ownerUid || "").trim().length > 0,
    productCount: Array.isArray(item.inventory) ? item.inventory.length : 0
  })).sort((a, b) => a.storeName.localeCompare(b.storeName, "es"));
  return { items, complete: snapshot.complete };
}
async function handleAction(env, action, data, identity) {
  switch (action) {
    case "dashboard": return dashboard(env);
    case "geoPresence":
    case "geoActivity": {
      const activeCutoff = Date.now() - 3 * 60 * 1000;
      const received = await queryRecentDocuments(env, "geoPresence", "updatedAt", 200);
      const records = received.filter(record => {
        const updatedAt = Date.parse(record.updatedAt || "");
        return Number.isFinite(updatedAt) && updatedAt >= activeCutoff && updatedAt <= Date.now() + 60 * 1000;
      });
      const profileUids = [...new Set(records
        .filter(record => record.consent === true && (!record.source || record.source === "GPS_ANDROID"))
        .map(record => String(record.uid || record.id || "").trim())
        .filter(Boolean))].slice(0, 200);
      // Resolver perfiles por UID evita perder marcadores cuando el listado general de usuarios
      // no incluye los documentos más recientes o usa una paginación distinta.
      const profileResults = await Promise.allSettled(profileUids.map(uid =>
        getDocument(env, "users", uid).then(document => document.missing ? null : documentData(document))
      ));
      const profiles = profileResults
        .map((result, index) => ({ result, uid: profileUids[index] }))
        .filter(item => item.result.status === "fulfilled" && item.result.value)
        .map(item => ({ ...item.result.value, uid: item.uid }));
      const profileLookupErrors = profileResults.filter(result => result.status === "rejected").length;
      const geo = aggregateGeoPresence(records, profiles);
      const consented = records.filter(record => record.consent === true);
      return {
        geoActivity: geo.points,
        geoActivitySummary: {
          activeUsers: geo.activeUsers,
          visibleMarkers: geo.points.length,
          freshnessMinutes: geo.freshnessMinutes,
          coordinatePrecision: "GPS individual con consentimiento explícito",
          sampleComplete: received.length < 200,
          sourceAvailable: true,
          recordsReceived: received.length,
          gpsRecords: received.filter(record => !record.source || record.source === "GPS_ANDROID").length,
          recentRecords: records.length,
          consentedRecords: consented.length,
          profilesRequested: profileUids.length,
          profilesResolved: profiles.length,
          profilesMissing: Math.max(0, profileUids.length - profiles.length),
          profileLookupErrors,
          invalidOrStaleRecords: Math.max(0, consented.length - geo.points.length)
        },
        diagnostics: {
          source: "geoPresence",
          presenceDocumentsRead: received.length,
          profileDocumentsRead: profiles.length,
          adminModule: "liveMap",
          refreshedAt: new Date().toISOString()
        }
      };
    }
    case "finance": return finance(env);
    case "demoMerchants": return demoMerchants(env);
    case "issueDemoMerchantClaimCode": return issueDemoMerchantCode(env, identity.uid, String(data.storeId || "").trim());
    case "list": {
      const collection = String(data.collection || "");
      const allowed = new Set(["users", "walletAccounts", "walletOperations", "merchantProfiles", "storeOrders", "storeReceipts", "storeReceiptAdjustments", "omniEvents", "omniMoments", "jobPosts", "omniProfessionalProfiles", "appFeedback", "usageEvents", "moduleRegistry", "adminAuditLogs", "betaInvitations"]);
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

const DEMO_SEEDS = [
 {id:"demo_kiosco_punto_24",name:"Punto 24 · Kiosco DEMO",type:"KIOSCO",sizeTier:"MICRO",description:"Kiosco ficticio para pruebas de compra y retiro.",hoursText:"Todos los días · 08:00 a 23:00",products:[["kp_agua","Agua mineral 2L","BEBIDAS",1100,"bot."],["kp_gaseosa","Gaseosa cola 1.5L","BEBIDAS",2450,"bot."],["kp_galletas","Galletitas surtidas 300g","KIOSCO",1450,"paq."],["kp_chocolate","Chocolate 90g","KIOSCO",1750,"unid."]]},
 {id:"demo_almacen_esquina",name:"Almacén La Esquina · DEMO",type:"ALMACEN",sizeTier:"PEQUENO",description:"Almacén barrial ficticio para probar catálogo y delivery.",hoursText:"Lun. a sáb. · 08:00 a 20:30",products:[["ae_leche","Leche entera 1L","LACTEOS",1450,"litro"],["ae_arroz","Arroz largo fino 1kg","ALMACEN",1850,"paq."],["ae_fideos","Fideos spaghetti 500g","ALMACEN",1350,"paq."],["ae_huevos","Huevos x 12","FRESCOS",3600,"doc."]]},
 {id:"demo_panaderia_trigal",name:"Panadería Trigal · DEMO",type:"PANADERIA",sizeTier:"PEQUENO",description:"Panadería ficticia para probar productos diarios y combos.",hoursText:"Todos los días · 06:30 a 19:30",products:[["pt_flauta","Pan francés 1kg","ALMACEN",2600,"kg"],["pt_medialunas","Medialunas x 6","ALMACEN",3900,"pack"],["pt_facturas","Facturas surtidas x 6","ALMACEN",3600,"pack"],["pt_chipa","Chipá x 250g","ALMACEN",2800,"paq."]]},
 {id:"demo_supermercado_norte",name:"Supermercado Norte · DEMO",type:"SUPERMERCADO",sizeTier:"GRANDE",description:"Supermercado ficticio para probar variedad y compras múltiples.",hoursText:"Lun. a sáb. · 07:30 a 21:00",products:[["sn_leche","Leche entera 1L","LACTEOS",1450,"litro"],["sn_arroz","Arroz largo fino 1kg","ALMACEN",1850,"paq."],["sn_aceite","Aceite girasol 900ml","ALMACEN",2600,"bot."],["sn_gaseosa","Gaseosa cola 1.5L","BEBIDAS",2450,"bot."]]},
 {id:"demo_tecnologia_pixel",name:"Pixel Lab · Tecnología DEMO",type:"SERVICIOS",sizeTier:"ESPECIALIZADO",description:"Tienda tecnológica ficticia para probar productos unitarios.",hoursText:"Lun. a sáb. · 09:00 a 19:00",products:[["pl_cable","Cable USB-C reforzado 1m","ALMACEN",4500,"unid."],["pl_cargador","Cargador USB-C 25W","ALMACEN",12500,"unid."],["pl_mouse","Mouse inalámbrico","ALMACEN",8900,"unid."],["pl_auris","Auriculares con micrófono","ALMACEN",7200,"unid."]]}
];
function seedProfile(seed) {
 const inventory=seed.products.map(([id,name,category,price,unitLabel])=>({id,name,category,price,originalPrice:null,inStock:true,stockQuantity:20,unitLabel,photoUri:null,barcode:null,description:name}));
 return {storeId:seed.id,storeName:seed.name,type:seed.type,environment:"DEMO",ownerUid:"",seedVersion:1,sizeTier:seed.sizeTier,capacityLabel:"Catálogo de prueba · entorno DEMO",description:seed.description,address:"Dirección ficticia · Entorno de prueba OMNI",phone:"",isOpen:true,hoursText:seed.hoursText,openTime:"08:00",closeTime:"20:00",pickupEnabled:true,deliveryEnabled:true,deliveryRadiusKm:50,deliveryFee:1200,freeDeliveryFrom:20000,merchantLatitude:-29.346,merchantLongitude:-59.967,minimumOrderAmount:0,estimatedPrepMinutes:20,cashback:{enabled:false,percentage:0,capPerPurchase:null,monthlyCap:null},comboDeals:[],inventory,news:[],createdAt:new Date().toISOString(),updatedAt:new Date().toISOString()};
}
async function provisionDemoCatalog(env) {
 const results=[];
 for(const seed of DEMO_SEEDS) {
  const existing=await getDocument(env,"merchantProfiles",seed.id);
  if(!existing.missing) {
   const current=decodeFields(existing.fields||{});
   if(current.environment!=="DEMO") results.push({storeId:seed.id,status:"OMITIDO_PERFIL_NO_DEMO"});
   else if(String(current.ownerUid||"").trim()) results.push({storeId:seed.id,status:"OMITIDO_YA_RECLAMADO"});
   else results.push({storeId:seed.id,status:"YA_EXISTE"});
   continue;
  }
  await firestore(env,":commit",{method:"POST",body:JSON.stringify({writes:[{update:{name:FIRESTORE_ROOT+"/merchantProfiles/"+seed.id,fields:encodeFields(seedProfile(seed))},currentDocument:{exists:false}}]})});
  results.push({storeId:seed.id,status:"CREADO",products:seed.products.length});
 }
 return {items:results,created:results.filter(x=>x.status==="CREADO").length};
}
async function claimDemoCatalogStore(env,uid,rawCode) {
 const code=String(rawCode||"").trim().toUpperCase();
 if(!/^OMNI-[A-F0-9]{16}$/.test(code)) throw new ApiError(400,"invalid-argument","El código de titularidad no tiene un formato válido.");
 const hash=await sha256Hex(code);
 const codePath=FIRESTORE_ROOT+"/demoMerchantClaimCodes/"+hash;
 const codeDoc=await getDocument(env,"demoMerchantClaimCodes",hash);
 if(codeDoc.missing) throw new ApiError(404,"not-found","El código no existe o ya fue utilizado.");
 const codeData=decodeFields(codeDoc.fields||{});
 if(codeData.active!==true||Date.parse(codeData.expiresAt||"")<=Date.now()) throw new ApiError(410,"failed-precondition","El código venció o ya fue utilizado.");
 const storeId=String(codeData.storeId||"");
 if(!DEMO_SEEDS.some(seed=>seed.id===storeId)) throw new ApiError(409,"failed-precondition","El código no corresponde al catálogo DEMO.");
 const storeDoc=await getDocument(env,"merchantProfiles",storeId);
 if(storeDoc.missing) throw new ApiError(404,"not-found","El comercio DEMO ya no está disponible.");
 const store=decodeFields(storeDoc.fields||{});
 if(store.environment!=="DEMO"||Number(store.seedVersion)!==1||String(store.ownerUid||"").trim()) throw new ApiError(409,"already-exists","El comercio ya tiene titular o no pertenece al catálogo DEMO.");
 const now=new Date().toISOString();
 await firestore(env,":commit",{method:"POST",body:JSON.stringify({writes:[
  {update:{name:codePath,fields:encodeFields({active:false,claimedByUid:uid,claimedAt:now})},updateMask:{fieldPaths:["active","claimedByUid","claimedAt"]},currentDocument:{updateTime:codeDoc.updateTime}},
  {update:{name:FIRESTORE_ROOT+"/merchantProfiles/"+storeId,fields:encodeFields({ownerUid:uid,claimedAt:now,updatedAt:now})},updateMask:{fieldPaths:["ownerUid","claimedAt","updatedAt"]},currentDocument:{updateTime:storeDoc.updateTime}}
 ]})});
 return {storeId,storeName:String(store.storeName||"Comercio DEMO"),ownerUid:uid,claimed:true};
}

function demoObject(value,label) {
 if(!value||typeof value!=="object"||Array.isArray(value)) throw new ApiError(400,"invalid-argument",label+" no tiene un formato válido.");
 return value;
}
function demoList(value,label,max) {
 if(!Array.isArray(value)||value.length>max||value.some(item=>!item||typeof item!=="object"||Array.isArray(item))) throw new ApiError(400,"invalid-argument",label+": formato o cantidad no válida.");
 return value;
}
function demoText(value,min,max,label,optional=false) {
 if(optional&&(value===null||value===undefined)) return "";
 if(typeof value!=="string"||value.trim().length<min||value.length>max) throw new ApiError(400,"invalid-argument",label+": longitud no válida.");
 return value;
}
function demoOnlyKeys(item,allowed,label) {
 if(Object.keys(item).some(key=>!allowed.includes(key))) throw new ApiError(400,"invalid-argument",label+": contiene campos no permitidos.");
}
function validateDemoProducts(raw,limit) {
 const products=demoList(raw,"Productos",limit), ids=new Set();
 return products.map((p,index)=>{
  const label="Producto "+(index+1);
  demoOnlyKeys(p,["id","name","category","price","originalPrice","inStock","stockQuantity","unitLabel","photoUri","barcode","description"],label);
  const id=demoText(p.id,1,100,label+" · ID").trim();
  if(ids.has(id)) throw new ApiError(400,"invalid-argument","Los identificadores de producto deben ser únicos.");
  ids.add(id);
  demoText(p.name,2,80,label+" · nombre");
  if(typeof p.price!=="number"||!Number.isFinite(p.price)||p.price<=0||p.price>50000) throw new ApiError(400,"invalid-argument",label+" · precio: importe no válido.");
  if(p.originalPrice!==null&&p.originalPrice!==undefined) {
   if(typeof p.originalPrice!=="number"||!Number.isFinite(p.originalPrice)||p.originalPrice<=p.price||p.originalPrice>100000) throw new ApiError(400,"invalid-argument",label+": el precio anterior debe superar al actual.");
  }
  const category=demoText(p.category,1,40,label+" · categoría");
  if(!/^[A-Z_]+$/.test(category)) throw new ApiError(400,"invalid-argument",label+": categoría no válida.");
  if(typeof p.inStock!=="boolean"||!Number.isInteger(p.stockQuantity)||p.stockQuantity<0||p.stockQuantity>100000||p.inStock!==(p.stockQuantity>0)) throw new ApiError(400,"invalid-argument",label+": stock no válido.");
  demoText(p.unitLabel,1,24,label+" · unidad");
  demoText(p.photoUri,0,1000,label+" · foto",true);
  demoText(p.barcode,0,64,label+" · código de barras",true);
  demoText(p.description,0,500,label+" · descripción");
  return p;
 });
}
function validateDemoCombos(raw) {
 return demoList(raw,"Combos",30).map((item,index)=>{
  const label="Combo "+(index+1);
  demoOnlyKeys(item,["title","description","discountBadge","audience"],label);
  demoText(item.title,2,80,label+" · título");
  demoText(item.description,0,300,label+" · descripción");
  demoText(item.discountBadge,0,24,label+" · etiqueta");
  demoText(item.audience,0,24,label+" · público");
  return item;
 });
}
function validateDemoNews(raw,storeId,storeName) {
 const ids=new Set();
 return demoList(raw,"Novedades",50).map((item,index)=>{
  const label="Novedad "+(index+1);
  demoOnlyKeys(item,["id","storeId","storeName","title","content","timeAgoText","badge","isHot"],label);
  const id=demoText(item.id,1,100,label+" · ID").trim();
  if(ids.has(id)) throw new ApiError(400,"invalid-argument","Los identificadores de novedades deben ser únicos.");
  ids.add(id);
  if(item.storeId!==storeId||item.storeName!==storeName) throw new ApiError(400,"invalid-argument",label+": no corresponde a este comercio.");
  demoText(item.title,2,100,label+" · título");
  demoText(item.content,0,1000,label+" · contenido");
  demoText(item.timeAgoText,0,40,label+" · fecha");
  demoText(item.badge,0,40,label+" · etiqueta");
  if(typeof item.isHot!=="boolean") throw new ApiError(400,"invalid-argument",label+" · indicador no válido.");
  return item;
 });
}
async function saveDemoMerchantCatalog(env,uid,data) {
 const storeId=typeof data.storeId==="string"?data.storeId.trim():"";
 if(!/^[A-Za-z0-9_-]{3,120}$/.test(storeId)) throw new ApiError(400,"invalid-argument","El identificador del comercio no es válido.");
 const txResult=await firestore(env,":beginTransaction",{method:"POST",body:JSON.stringify({options:{readWrite:{}}})});
 const transaction=txResult.transaction;
 if(!transaction) throw new Error("No se pudo iniciar la transacción del catálogo DEMO.");
 const profileName=FIRESTORE_ROOT+"/merchantProfiles/"+encodeURIComponent(storeId);
 const batch=await firestore(env,":batchGet",{method:"POST",body:JSON.stringify({documents:[profileName],transaction})});
 const profile=Array.isArray(batch)?batch.find(item=>item.found)?.found:null;
 if(!profile) throw new ApiError(404,"not-found","El comercio DEMO no existe.");
 const current=decodeFields(profile.fields||{});
 if(current.environment!=="DEMO"||Number(current.seedVersion)!==1||current.ownerUid!==uid||current.storeId!==storeId) throw new ApiError(403,"permission-denied","No sos el titular habilitado de este comercio DEMO.");
 const limits={MICRO:20,PEQUENO:50,MEDIANO:100,GRANDE:250,ESPECIALIZADO:50};
 const limit=limits[String(current.sizeTier||"")];
 if(!limit) throw new ApiError(412,"failed-precondition","El comercio no tiene un tamaño DEMO válido.");
 const inventory=validateDemoProducts(data.inventory,limit);
 const combos=validateDemoCombos(data.comboDeals);
 const news=validateDemoNews(data.news,storeId,String(current.storeName||""));
 const previous=Array.isArray(current.inventory)?current.inventory:[];
 const previousById=new Map(previous.map(item=>[String(item.id||""),item]));
 const now=new Date().toISOString();
 const writes=[];
 for(const product of inventory) {
  const beforeItem=previousById.get(String(product.id));
  const before=Number.isInteger(Number(beforeItem?.stockQuantity))?Number(beforeItem.stockQuantity):0;
  const after=Number(product.stockQuantity);
  if(before===after) continue;
  const movementId=randomHex(12).toLowerCase();
  writes.push({update:{name:profileName+"/inventoryMovements/"+movementId,fields:encodeFields({
   movementId,storeId,productId:String(product.id),productName:String(product.name),
   type:beforeItem?"AJUSTE_TITULAR":"ALTA_INVENTARIO",quantityDelta:after-before,
   stockBefore:before,stockAfter:after,actorUid:uid,environment:"DEMO",createdAt:now
  })},currentDocument:{exists:false}});
 }
 writes.push({update:{name:profileName,fields:encodeFields({
  inventory,comboDeals:combos,news,catalogUpdatedByUid:uid,catalogUpdatedAt:now,updatedAt:now
 })},updateMask:{fieldPaths:["inventory","comboDeals","news","catalogUpdatedByUid","catalogUpdatedAt","updatedAt"]}});
 await firestore(env,":commit",{method:"POST",body:JSON.stringify({transaction,writes})});
 return {saved:true,storeId,products:inventory.length,combos:combos.length,news:news.length};
}


async function createDemoStoreOrder(env,uid,data,identity) {
 const storeId=String(data.storeId||"").trim();
 const fulfillmentType=String(data.fulfillmentType||"RETIRO");
 const address=String(data.deliveryAddress||"").trim();
 if(!/^[A-Za-z0-9_-]{1,120}$/.test(storeId)||!["RETIRO","DELIVERY"].includes(fulfillmentType))
  throw new ApiError(400,"invalid-argument","El comercio o la modalidad del pedido no son válidos.");
 if(fulfillmentType==="DELIVERY"&&(address.length<5||address.length>240))
  throw new ApiError(400,"invalid-argument","Ingresá una dirección de entrega válida.");
 const items=demoList(data.items,"Productos del pedido",30);
 if(items.length<1) throw new ApiError(400,"invalid-argument","El pedido debe contener al menos un producto.");
 const profileDoc=await getDocument(env,"merchantProfiles",storeId);
 if(profileDoc.missing) throw new ApiError(404,"not-found","El comercio DEMO no existe.");
 const profile=decodeFields(profileDoc.fields||{});
 if(profile.environment!=="DEMO"||Number(profile.seedVersion)!==1||profile.storeId!==storeId)
  throw new ApiError(412,"failed-precondition","El comercio no está habilitado para pedidos DEMO.");
 const merchantUid=String(profile.ownerUid||"");
 if(!merchantUid||merchantUid===uid) throw new ApiError(412,"failed-precondition","El comercio todavía no tiene un titular habilitado para recibir pedidos.");
 if(fulfillmentType==="RETIRO"&&profile.pickupEnabled===false) throw new ApiError(412,"failed-precondition","El comercio no habilitó el retiro.");
 let deliveryLatitude=null,deliveryLongitude=null;
 if(fulfillmentType==="DELIVERY") {
  const merchantLat=Number(profile.merchantLatitude),merchantLon=Number(profile.merchantLongitude);
  const buyerLat=Number(data.deliveryLatitude),buyerLon=Number(data.deliveryLongitude);
  if(profile.deliveryEnabled!==true||![merchantLat,merchantLon,buyerLat,buyerLon].every(Number.isFinite)||
    Math.abs(merchantLat)>90||Math.abs(buyerLat)>90||Math.abs(merchantLon)>180||Math.abs(buyerLon)>180)
   throw new ApiError(412,"failed-precondition","El comercio o tu dispositivo no tienen una ubicación válida para verificar la cobertura.");
  const rad=v=>v*Math.PI/180, dLat=rad(buyerLat-merchantLat),dLon=rad(buyerLon-merchantLon);
  const h=Math.sin(dLat/2)**2+Math.cos(rad(merchantLat))*Math.cos(rad(buyerLat))*Math.sin(dLon/2)**2;
  const distance=6371*2*Math.atan2(Math.sqrt(h),Math.sqrt(1-h));
  const radius=Number(profile.deliveryRadiusKm);
  if(!Number.isFinite(radius)||radius<=0||distance>radius+0.05) throw new ApiError(400,"out-of-range","La dirección está fuera del radio de delivery de este comercio.");
  deliveryLatitude=buyerLat;deliveryLongitude=buyerLon;
 }
 const catalog=Array.isArray(profile.inventory)?profile.inventory:[];
 const seen=new Set();let subtotal=0;
 const normalizedItems=items.map((line,index)=>{
  const productId=String(line.productId||"");
  const quantity=Number(line.quantity),price=Number(line.price);
  if(!productId||seen.has(productId)||!Number.isInteger(quantity)||quantity<1||quantity>999||!Number.isFinite(price)||price<=0)
   throw new ApiError(400,"invalid-argument","Hay productos o cantidades inválidas en el pedido.");
  seen.add(productId);
  const current=catalog.find(item=>String(item.id||"")===productId);
  const stock=Number(current?.stockQuantity);
  if(!current||!Number.isInteger(stock)||stock<quantity||current.inStock!==(stock>0)||Math.abs(Number(current.price)-price)>=0.005)
   throw new ApiError(412,"failed-precondition","Cambió el precio o la disponibilidad de un producto. Actualizá el carrito.");
  const name=String(current.name||"");
  if(!name||name.length>80||String(line.name||"")!==name) throw new ApiError(412,"failed-precondition","Cambió el nombre de un producto. Actualizá el carrito.");
  subtotal+=price*quantity;
  return {productId,name,price,quantity};
 });
 subtotal=Math.round(subtotal*100)/100;
 const minimum=Number(profile.minimumOrderAmount??0);
 const freeRaw=profile.freeDeliveryFrom,freeFrom=freeRaw==null?null:Number(freeRaw);
 const fee=Number(profile.deliveryFee??0);
 if(!Number.isFinite(minimum)||minimum<0||minimum>50000||(freeFrom!==null&&(!Number.isFinite(freeFrom)||freeFrom<0||freeFrom>50000))||!Number.isFinite(fee)||fee<0||fee>50000)
  throw new ApiError(412,"failed-precondition","La configuración de compra mínima o envío no es válida.");
 if(subtotal<=0||subtotal>50000||subtotal<minimum) throw new ApiError(412,"failed-precondition","El pedido no alcanza la compra mínima o supera el máximo DEMO.");
 const deliveryFee=fulfillmentType==="DELIVERY"&&!(freeFrom!==null&&subtotal>=freeFrom)?fee:0;
 const total=Math.round((subtotal+deliveryFee)*100)/100;
 if(!Number.isFinite(Number(data.expectedTotal))||Math.abs(Number(data.expectedTotal)-total)>=0.005)
  throw new ApiError(412,"failed-precondition","El total cambió desde la revisión del carrito. Actualizá el pedido.");
 const cashback=profile.cashback&&typeof profile.cashback==="object"?profile.cashback:{enabled:false,percentage:0,capPerPurchase:null,monthlyCap:null};
 const expected=data.expectedCashbackPolicy||{};
 if(expected.enabled!==cashback.enabled||Number(expected.percentage||0)!==Number(cashback.percentage||0)||
  (expected.capPerPurchase??null)!==(cashback.capPerPurchase??null)||(expected.monthlyCap??null)!==(cashback.monthlyCap??null))
  throw new ApiError(412,"failed-precondition","El comercio actualizó las condiciones de cashback. Volvé a revisar.");
 const orderId="ord_"+randomHex(16).toLowerCase(),qrNonce=randomHex(48).toLowerCase(),now=new Date().toISOString();
 const customerName=String(identity.name||identity.email||"Cliente OMNI").slice(0,80);
 const order={storeId,storeName:String(profile.storeName||"Comercio DEMO"),environment:"DEMO",merchantUid,customerUid:uid,
  customerName,customerPhone:String(data.customerPhone||"").slice(0,40),items:normalizedItems,subtotal,deliveryFee,total,cashbackPolicy:cashback,
  status:"RECIBIDO",paymentStatus:"PENDIENTE",paymentMethod:"PAGO_AL_RETIRAR",fulfillmentType,
  deliveryAddress:fulfillmentType==="DELIVERY"?address:"",...(deliveryLatitude!==null?{deliveryLatitude,deliveryLongitude}:{}),
  qrNonce,operationId:orderId,createdAt:now,updatedAt:now};
 await firestore(env,":commit",{method:"POST",body:JSON.stringify({writes:[
  {update:{name:FIRESTORE_ROOT+"/storeOrders/"+orderId,fields:encodeFields(order)},currentDocument:{exists:false}},
  {update:{name:FIRESTORE_ROOT+"/storeOrders/"+orderId+"/statusHistory/"+randomHex(12).toLowerCase(),fields:encodeFields({
   orderId,storeId,environment:"DEMO",actorUid:uid,actorRole:"COMPRADOR",fromStatus:"",toStatus:"RECIBIDO",
   eventType:"ESTADO_PEDIDO",note:"Pedido creado y enviado al comercio",createdAt:now
  })},currentDocument:{exists:false}}
 ]})});
 return {orderId,qrNonce,subtotal,deliveryFee,total,items:normalizedItems,storeId,storeName:order.storeName,merchantUid,cashbackPolicy:cashback,deliveryLatitude,deliveryLongitude};
}

async function handleDemoMerchantRequest(request,env) {
 if(request.method!=="POST") return json({ok:false,error:"method-not-allowed",message:"Usá POST."},405);
 const authHeader=request.headers.get("Authorization")||"";
 if(!authHeader.startsWith("Bearer ")) throw new ApiError(401,"unauthenticated","Iniciá sesión en OMNI.");
 const identity=await verifyFirebaseToken(authHeader.slice(7).trim(),PRIMARY_PROJECT);
 const body=await request.json();
 const data=body.data&&typeof body.data==="object"?body.data:{};
 if(body.action==="provision") return json({ok:true,result:await provisionDemoCatalog(env)});
 if(body.action==="createOrder") return json({ok:true,result:await createDemoStoreOrder(env,identity.uid,data,identity)});
 if(body.action==="claim") return json({ok:true,result:await claimDemoCatalogStore(env,identity.uid,data.code)});
 if(body.action==="saveCatalog") return json({ok:true,result:await saveDemoMerchantCatalog(env,identity.uid,data)});
 throw new ApiError(400,"invalid-argument","Acción DEMO desconocida.");
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
  const accuracyMeters = Number(data.accuracyMeters);
  const locationTime = Number(data.locationTimeMillis);
  const now = Date.now();
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude) ||
      Math.abs(latitude) > 90 || Math.abs(longitude) > 180 ||
      !Number.isFinite(accuracyMeters) || accuracyMeters <= 0 || accuracyMeters > 10000 ||
      !Number.isFinite(locationTime) || now - locationTime > 2 * 60 * 1000 || locationTime > now + 60 * 1000) {
    throw new ApiError(400, "invalid-argument", "Se requiere una posición GPS válida, reciente y con precisión informada.");
  }
  await writeDocument(env, "geoPresence", identity.uid, {
    uid: identity.uid,
    latitude,
    longitude,
    accuracyMeters,
    locationTime: new Date(locationTime).toISOString(),
    locationTimeMillis: locationTime,
    locality: String(data.locality || "").trim().slice(0, 80) || "Localidad no informada",
    province: String(data.province || "").trim().slice(0, 80) || "Provincia no informada",
    country: String(data.country || "").trim().slice(0, 80) || "País no informado",
    consent: true,
    source: "GPS_ANDROID",
    updatedAt: new Date().toISOString()
  });
  return json({ ok: true, result: { saved: true, precision: "gps-exact", accuracyMeters: Math.round(accuracyMeters) } });
}

export default {
  async fetch(request, env) {
    const requestUrl = new URL(request.url);
    if (requestUrl.pathname === "/demo-merchant") {
      try { return await handleDemoMerchantRequest(request, env); }
      catch (error) {
        if (error instanceof ApiError) return json({ ok:false, error:error.code, message:error.message }, error.status);
        console.error("OMNI DEMO merchant API error", error);
        return json({ ok:false, error:"internal", message:"No se pudo completar la operación DEMO." }, 500);
      }
    }
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
      const result = await handleAction(env, String(body.action || ""), body.data && typeof body.data === "object" ? body.data : {}, identity);
      return json({ ok: true, result });
    } catch (error) {
      if (error instanceof ApiError) return json({ ok: false, error: error.code, message: error.message }, error.status);
      console.error("OMNI Admin API error", error);
      return json({ ok: false, error: "internal", message: "No se pudo completar la operación." }, 500);
    }
  }
};
