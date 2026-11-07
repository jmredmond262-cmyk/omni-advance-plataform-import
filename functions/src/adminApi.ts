import { initializeApp, getApps } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { FieldPath, FieldValue, getFirestore, Timestamp, type QuerySnapshot } from "firebase-admin/firestore";
import { defineString } from "firebase-functions/params";
import { HttpsError, onRequest } from "firebase-functions/v2/https";

const REGION = "us-central1";
const ADMIN_AUTH_PROJECT_ID = defineString("OMNI_ADMIN_AUTH_PROJECT_ID");
const primaryApp = getApps().find((app) => app.name === "[DEFAULT]") ?? initializeApp();
const db = getFirestore(primaryApp);
function getAuthVerifier() {
  const app = getApps().find((item) => item.name === "omni-admin-auth-verifier")
    ?? initializeApp({ projectId: ADMIN_AUTH_PROJECT_ID.value() }, "omni-admin-auth-verifier");
  return getAuth(app);
}

type AdminContext = { uid: string; email: string; role: "owner" | "admin" };

function bearerToken(header: string | undefined): string {
  if (!header?.startsWith("Bearer ")) throw new HttpsError("unauthenticated", "Falta la sesión administrativa.");
  return header.slice(7).trim();
}

async function authenticate(header: string | undefined): Promise<AdminContext> {
  const decoded = await getAuthVerifier().verifyIdToken(bearerToken(header));
  const adminSnap = await db.collection("systemAdmins").doc(decoded.uid).get();
  const role = adminSnap.get("role");
  if (!adminSnap.exists || adminSnap.get("enabled") !== true || (role !== "owner" && role !== "admin")) {
    throw new HttpsError("permission-denied", "Esta cuenta no está habilitada como administradora de OMNI.");
  }
  return { uid: decoded.uid, email: String(decoded.email ?? ""), role };
}

function clean(value: unknown): unknown {
  if (value instanceof Timestamp) return value.toDate().toISOString();
  if (Array.isArray(value)) return value.map(clean);
  if (value && typeof value === "object") {
    const source = value as Record<string, unknown>;
    return Object.fromEntries(Object.entries(source).map(([key, item]) => [key, clean(item)]));
  }
  if (typeof value === "bigint") return Number(value);
  return value;
}

function docList(snapshot: QuerySnapshot, limit = 100) {
  return snapshot.docs.slice(0, limit).map((doc) => ({ id: doc.id, ...clean(doc.data()) as object }));
}

async function count(collection: string): Promise<number> {
  const result = await db.collection(collection).count().get();
  return result.data().count;
}

async function audit(actor: AdminContext, action: string, target: string, details: Record<string, unknown> = {}) {
  await db.collection("adminAuditLogs").add({
    actorUid: actor.uid, actorEmail: actor.email, action, target,
    details, createdAt: FieldValue.serverTimestamp()
  });
}

async function dashboard() {
  const collections = ["users", "walletAccounts", "walletOperations", "merchantProfiles", "storeOrders", "omniEvents", "omniMoments", "jobPosts", "omniProfessionalProfiles", "appFeedback", "usageEvents", "moduleRegistry"];
  const counts = await Promise.all(collections.map(count));
  const since = Timestamp.fromDate(new Date(Date.now() - 7 * 24 * 60 * 60 * 1000));
  const [wallets, users, operations, usage, profiles] = await Promise.all([
    db.collection("walletAccounts").limit(1000).get(),
    db.collection("users").orderBy("createdAt", "desc").limit(8).get(),
    db.collection("walletOperations").orderBy("createdAt", "desc").limit(8).get(),
    db.collection("usageEvents").where("createdAt", ">=", since).limit(5000).get(),
    db.collection("users").limit(5000).get()
  ]);
  const balance = wallets.docs.reduce((sum, doc) => sum + (Number(doc.get("balance")) || 0), 0);
  const events: Array<Record<string, unknown>> = usage.docs.map((doc) => ({ id: doc.id, ...doc.data() }));
  const valueOf = (event: Record<string, unknown>, keys: string[]) =>
    keys.map((key) => event[key]).find((value) => value !== undefined && value !== null);
  const eventName = (event: Record<string, unknown>) => String(valueOf(event, ["eventType", "type", "event", "name", "action"]) ?? "TIPO_DE_EVENTO_NO_INFORMADO");
  const screenName = (event: Record<string, unknown>) => String(valueOf(event, ["screen", "screenName", "route"]) ?? "PANTALLA_NO_INFORMADA");
  const moduleName = (event: Record<string, unknown>) => String(valueOf(event, ["module", "moduleId", "feature"]) ?? "MODULO_NO_INFORMADO");
  const countBy = (values: string[]) => Object.entries(values.filter(Boolean).reduce<Record<string, number>>((acc, value) => {
    acc[value] = (acc[value] ?? 0) + 1; return acc;
  }, {})).map(([name, value]) => ({ name, count: value })).sort((a, b) => b.count - a.count);
  const sessions = new Set(events.map((event) => String(valueOf(event, ["sessionId", "session_id"]) ?? "")).filter(Boolean));
  const activeUsers = new Set(events.map((event) => String(valueOf(event, ["uid", "userId", "userUid"]) ?? "")).filter(Boolean));
  const screenEvents = events.filter((event) => /screen|view|navigation/i.test(eventName(event)));
  const actionEvents = events.filter((event) => !/screen|view|navigation/i.test(eventName(event)));
  const dayCounts: Record<string, number> = {};
  const todayUtc = new Date();
  todayUtc.setUTCHours(0, 0, 0, 0);
  for (let offset = 6; offset >= 0; offset--) {
    const day = new Date(todayUtc); day.setUTCDate(day.getUTCDate() - offset);
    const key = day.toISOString().slice(0, 10);
    dayCounts[key] = 0;
  }
  const hourlyCounts = Array.from({ length: 24 }, (_, hour) => ({ hour, count: 0 }));
  const weekdayCounts = Array.from({ length: 7 }, (_, index) => ({ day: index, label: ["Domingo", "Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado"][index], count: 0 }));
  const sessionDurations: number[] = [];
  const screenDurations: number[] = [];
  for (const event of events) {
    const raw = valueOf(event, ["createdAt", "timestamp", "serverTimestamp"]);
    const date = raw instanceof Timestamp ? raw.toDate() : raw instanceof Date ? raw : null;
    const utcDate = date ? date.toISOString().slice(0, 10) : "";
    if (utcDate in dayCounts) dayCounts[utcDate]++;
    const hourRaw = valueOf(event, ["localHour"]);
    const hour = Number(hourRaw);
    if (Number.isInteger(hour) && hour >= 0 && hour <= 23) hourlyCounts[hour].count++;
    const weekdayRaw = Number(valueOf(event, ["localDayOfWeek"]));
    if (Number.isInteger(weekdayRaw) && weekdayRaw >= 1 && weekdayRaw <= 7) weekdayCounts[weekdayRaw - 1].count++;
    const data = event.data && typeof event.data === "object" ? event.data as Record<string, unknown> : {};
    const sessionDuration = Number(data.sessionDurationSeconds ?? event.sessionDurationSeconds ?? 0);
    const screenDuration = Number(data.screenDurationSeconds ?? event.screenDurationSeconds ?? 0);
    if (eventName(event) === "SESSION_END" && Number.isFinite(sessionDuration) && sessionDuration > 0) sessionDurations.push(sessionDuration);
    if (eventName(event) === "SCREEN_EXIT" && Number.isFinite(screenDuration) && screenDuration > 0) screenDurations.push(screenDuration);
  }
  const observedHours = hourlyCounts.filter((item) => item.count > 0);
  const peakActivityHour = observedHours.length ? observedHours.reduce((best, item) => item.count > best.count ? item : best) : null;
  const quietestActivityHour = observedHours.length ? observedHours.reduce((best, item) => item.count < best.count ? item : best) : null;
  const diagnosticEventSeen = events.some((event) => /diagnostic|health.?check/i.test(eventName(event)));
  return {
    counts: Object.fromEntries(collections.map((name, index) => [name, counts[index]])),
    activeUsers7d: activeUsers.size,
    analytics: {
      sessions7d: sessions.size,
      screenViews7d: screenEvents.length,
      actions7d: actionEvents.length,
      averageSessionSeconds: sessionDurations.length ? Math.round(sessionDurations.reduce((sum, value) => sum + value, 0) / sessionDurations.length) : null,
      averageScreenSeconds: screenDurations.length ? Math.round(screenDurations.reduce((sum, value) => sum + value, 0) / screenDurations.length) : null,
      measuredSessions: sessionDurations.length,
      measuredScreens: screenDurations.length,
      hourlyActivity: hourlyCounts,
      weekdayActivity: weekdayCounts,
      peakActivityHour,
      quietestActivityHour,
      featureMetrics: {
        aiCameraScans: events.filter((event) => /camera|scan|image.?analysis/i.test(eventName(event))).length,
        mapsInteractions: events.filter((event) => /map|route|place/i.test(eventName(event) + " " + moduleName(event))).length,
        jobInteractions: events.filter((event) => /job|work|employment/i.test(eventName(event) + " " + moduleName(event))).length,
        eventInteractions: events.filter((event) => /event/i.test(eventName(event) + " " + moduleName(event))).length,
        routineWeatherInteractions: events.filter((event) => /routine|weather|forecast/i.test(eventName(event) + " " + moduleName(event))).length,
        commerceShares: events.filter((event) => /share|whatsapp/i.test(eventName(event))).length
      },
      topScreens: countBy(screenEvents.map(screenName)).slice(0, 8),
      moduleUsage: countBy(events.map(moduleName)).slice(0, 10),
      eventBreakdown: countBy(events.map(eventName)).slice(0, 12)
    },
    usageTrend: Object.entries(dayCounts).map(([date, value]) => ({ date, count: value })),
    diagnostics: {
      usageEventsVisibleLast7Days: usage.size,
      diagnosticEventSeen,
      userProfilesVisible: profiles.size
    },
    walletBalanceSample: balance,
    walletSampleSize: wallets.size,
    recentUsers: docList(users, 8),
    recentOperations: docList(operations, 8),
    recentUsageEvents: docList(usage, 20)
  };
}

async function listCollection(name: string, limit = 100, pageToken = "") {
  const allowed = new Set(["users", "walletAccounts", "walletOperations", "merchantProfiles", "storeOrders", "omniEvents", "omniMoments", "jobPosts", "omniProfessionalProfiles", "appFeedback", "usageEvents", "moduleRegistry", "adminAuditLogs", "betaInvitations"]);
  if (!allowed.has(name)) throw new HttpsError("invalid-argument", "Colección no disponible.");
  const safeLimit = Math.min(Math.max(limit, 1), 200);
  let query = db.collection(name).orderBy(FieldPath.documentId()).limit(safeLimit + 1);
  if (pageToken) {
    const cursor = await db.collection(name).doc(pageToken).get();
    if (!cursor.exists) throw new HttpsError("invalid-argument", "El cursor de paginación ya no existe. Actualizá el listado.");
    query = query.startAfter(cursor);
  }
  const snapshot = await query.get();
  const hasMore = snapshot.docs.length > safeLimit;
  const page = snapshot.docs.slice(0, safeLimit);
  const orderPayments = new Map<string, Record<string, unknown>>();
  if (name === "storeOrders" && page.length > 0) {
    const orderIds = page.map((doc) => doc.id);
    for (let offset = 0; offset < orderIds.length; offset += 30) {
      const chunk = orderIds.slice(offset, offset + 30);
      const payments = await db.collection("walletOperations").where("orderId", "in", chunk).get();
      for (const payment of payments.docs) {
        const paymentData = payment.data() as Record<string, unknown>;
        if (paymentData.kind === "QR_PAYMENT" && paymentData.environment === "DEMO") {
          const orderId = String(paymentData.orderId ?? "");
          if (orderId) orderPayments.set(orderId, { operationId: payment.id, createdAt: paymentData.createdAt });
        }
      }
    }
  }
  return {
    items: page.map((doc) => {
      const data = clean(doc.data()) as Record<string, unknown>;
      if (name === "storeOrders") {
        const payment = orderPayments.get(doc.id);
        return {
          id: doc.id, ...data,
          paymentStatus: payment ? "PAGADA · DEMO" : "PENDIENTE",
          paymentEnvironment: "DEMO",
          paymentOperationId: payment?.operationId ?? null,
          paymentCreatedAt: payment?.createdAt ?? null
        };
      }
      if (name === "omniMoments") {
        // Private greeting text, personal notes and names are never exposed in Admin listings.
        return {
          id: doc.id, ownerUid: data.ownerUid, occasion: data.occasion,
          date: data.date, status: data.status, createdAt: data.createdAt,
          updatedAt: data.updatedAt
        };
      }
      if (name === "omniProfessionalProfiles") {
        // CV links, skills, education and experience remain private even to aggregate Admin views.
        return {
          id: doc.id, ownerUid: data.ownerUid, profession: data.profession,
          location: data.location, availability: data.availability,
          visibility: data.visibility, updatedAt: data.updatedAt
        };
      }
      return { id: doc.id, ...data };
    }),
    nextPageToken: hasMore ? page[page.length - 1]?.id ?? "" : "",
    hasMore
  };
}

async function handleAction(actor: AdminContext, action: string, data: Record<string, unknown>) {
  switch (action) {
    case "dashboard":
      return dashboard();
    case "list": {
      const collection = String(data.collection ?? "");
      return await listCollection(collection, Number(data.limit) || 100, String(data.pageToken ?? ""));
    }
    case "getConfig": {
      const snap = await db.collection("adminConfig").doc("global").get();
      return { config: snap.exists ? clean(snap.data()) : {} };
    }
    case "saveConfig": {
      if (actor.role !== "owner") throw new HttpsError("permission-denied", "Solo el propietario puede modificar la configuración global.");
      const allowed = ["maintenanceMode", "maintenanceMessage", "minimumVersion", "recommendedVersion", "globalNotice", "featureFlags", "updatedAt"];
      const patch = Object.fromEntries(Object.entries((data.config ?? {}) as Record<string, unknown>).filter(([key]) => allowed.includes(key) && key !== "updatedAt"));
      if (!Object.keys(patch).length) throw new HttpsError("invalid-argument", "No hay parámetros válidos para guardar.");
      await db.collection("adminConfig").doc("global").set({ ...patch, updatedAt: FieldValue.serverTimestamp(), updatedBy: actor.uid }, { merge: true });
      await audit(actor, "CONFIG_UPDATED", "adminConfig/global", { fields: Object.keys(patch) });
      return { saved: true };
    }
    case "saveModule": {
      if (actor.role !== "owner") throw new HttpsError("permission-denied", "Solo el propietario puede modificar módulos.");
      const id = String(data.id ?? "").trim();
      if (!/^[A-Za-z0-9_-]{2,64}$/.test(id)) throw new HttpsError("invalid-argument", "Identificador de módulo inválido.");
      const input = (data.module ?? {}) as Record<string, unknown>;
      const patch = {
        title: String(input.title ?? id).slice(0, 80),
        description: String(input.description ?? "").slice(0, 500),
        enabled: input.enabled === true,
        updatedAt: FieldValue.serverTimestamp(),
        updatedBy: actor.uid
      };
      await db.collection("moduleRegistry").doc(id).set(patch, { merge: true });
      await audit(actor, "MODULE_UPDATED", `moduleRegistry/${id}`, { enabled: patch.enabled });
      return { saved: true };
    }
    case "createInvitation": {
      const email = String(data.email ?? "").trim().toLowerCase();
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) throw new HttpsError("invalid-argument", "Ingresá un correo válido.");
      const ref = await db.collection("betaInvitations").add({
        email, role: "beta", status: "PENDING", createdAt: FieldValue.serverTimestamp(), createdBy: actor.uid
      });
      await audit(actor, "BETA_INVITATION_CREATED", ref.path, { email });
      return { id: ref.id, created: true };
    }
    default:
      throw new HttpsError("invalid-argument", "Acción administrativa desconocida.");
  }
}

export const omniAdminApi = onRequest({ region: REGION, timeoutSeconds: 60, memory: "512MiB" }, async (request, response) => {
  response.set("Cache-Control", "no-store");
  if (request.method !== "POST") {
    response.status(405).json({ error: "method-not-allowed", message: "Usá POST." });
    return;
  }
  try {
    const actor = await authenticate(request.header("authorization"));
    const action = String(request.body?.action ?? "");
    const data = (request.body?.data ?? {}) as Record<string, unknown>;
    const result = await handleAction(actor, action, data);
    response.status(200).json({ ok: true, result });
  } catch (error) {
    const authCode = (error as { code?: string })?.code ?? "";
    const code = error instanceof HttpsError ? error.code : authCode.startsWith("auth/") ? "unauthenticated" : "internal";
    const message = error instanceof Error ? error.message : "Error interno.";
    const status = code === "unauthenticated" ? 401 : code === "permission-denied" ? 403
      : code === "invalid-argument" ? 400 : 500;
    if (status === 500) console.error("omniAdminApi failed", error);
    response.status(status).json({ ok: false, error: code, message: status === 500 ? "No se pudo completar la operación." : code === "unauthenticated" ? "La sesión administrativa no es válida o venció. Ingresá nuevamente." : message });
  }
});
