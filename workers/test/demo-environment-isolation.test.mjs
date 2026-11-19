import test from "node:test";
import assert from "node:assert/strict";
import { firebaseProjectId, firestoreRoot } from "../src/index.js";

test("existing Worker default remains on the configured production project", () => {
  assert.equal(firebaseProjectId({}), "omni-57f69");
  assert.equal(firestoreRoot({}), "https://firestore.googleapis.com/v1/projects/omni-57f69/databases/(default)/documents");
});

test("DEMO Worker resolves its separate Firebase project", () => {
  const env = { OMNI_ENVIRONMENT: "DEMO", OMNI_FIREBASE_PROJECT_ID: "omni-demo-lab" };
  assert.equal(firebaseProjectId(env), "omni-demo-lab");
  assert.equal(firestoreRoot(env), "https://firestore.googleapis.com/v1/projects/omni-demo-lab/databases/(default)/documents");
});

test("DEMO Worker refuses to start without an independent project id", () => {
  assert.throws(() => firebaseProjectId({ OMNI_ENVIRONMENT: "DEMO" }), /proyecto Firebase DEMO independiente/);
});

test("DEMO Worker cannot point to production Firebase", () => {
  assert.throws(() => firebaseProjectId({ OMNI_ENVIRONMENT: "DEMO", OMNI_FIREBASE_PROJECT_ID: "omni-57f69" }), /proyecto Firebase DEMO independiente/);
});

test("malformed project ids are rejected", () => {
  assert.throws(() => firebaseProjectId({ OMNI_ENVIRONMENT: "DEMO", OMNI_FIREBASE_PROJECT_ID: "invalid/project" }), /no es válido/);
});
