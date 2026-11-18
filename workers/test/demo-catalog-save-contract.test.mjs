import assert from "node:assert/strict";
import test from "node:test";
import { validateDemoOperationalFields } from "../src/index.js";

const validOperational = {
  address: "Dirección ficticia · Entorno de prueba OMNI",
  phone: "",
  description: "Comercio DEMO",
  isOpen: true,
  hoursText: "Todos los días · 08:00 a 23:00",
  openTime: "08:00",
  closeTime: "20:00",
  weekendHoursText: "Sábados · 09:00 a 14:00",
  is24Hours: false,
  isOnDuty: false,
  openOnHolidays: false,
  scheduleNotes: "",
  pickupEnabled: true,
  deliveryEnabled: true,
  deliveryRadiusKm: 50,
  deliveryFee: 1200,
  freeDeliveryFrom: 20000,
  minimumOrderAmount: 0,
  estimatedPrepMinutes: 20,
  merchantLatitude: -29.346,
  merchantLongitude: -59.967,
  minSpendDiscountThreshold: 0,
  minSpendDiscountPercent: 0,
  specialOfferText: "",
  cashback: {
    enabled: false,
    percentage: 0,
    capPerPurchase: null,
    monthlyCap: null
  }
};

test("validates the full DEMO operational profile contract", () => {
  const validated = validateDemoOperationalFields(validOperational);
  assert.equal(validated.deliveryEnabled, true);
  assert.equal(validated.cashback.enabled, false);
  assert.equal(validated.deliveryRadiusKm, 50);
  assert.equal(validated.merchantLatitude, -29.346);
});

test("rejects partial or invalid operational updates before persistence", () => {
  const { cashback, ...missingCashback } = validOperational;
  assert.throws(() => validateDemoOperationalFields(missingCashback), /Faltan campos operativos/);
  assert.throws(() => validateDemoOperationalFields({
    ...validOperational,
    openTime: "8:00"
  }), /HH:mm/);
  assert.throws(() => validateDemoOperationalFields({
    ...validOperational,
    merchantLongitude: null
  }), /coordenadas válidas y completas/);
  assert.throws(() => validateDemoOperationalFields({
    ...validOperational,
    cashback: { ...cashback, enabled: true, percentage: 0 }
  }), /porcentaje mayor que cero/);
});

test("allows absent optional limits and zero-valued valid coordinates when delivery is off", () => {
  const {
    freeDeliveryFrom,
    merchantLatitude,
    merchantLongitude,
    ...withoutOptionalLimits
  } = validOperational;
  const validated = validateDemoOperationalFields({
    ...withoutOptionalLimits,
    deliveryEnabled: false,
    merchantLatitude: 0,
    merchantLongitude: 0
  });
  assert.equal(validated.freeDeliveryFrom, null);
  assert.equal(validated.merchantLatitude, 0);
  assert.equal(validated.merchantLongitude, 0);
});
