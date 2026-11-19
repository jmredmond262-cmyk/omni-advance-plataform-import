import test from "node:test";
import assert from "node:assert/strict";
import { firebaseProjectId, firestoreRoot } from "../src/index.js";

test("production-compatible default remains unchanged when no environment is selected", () => {
  assert.equal(firebaseProjectId({}), "omni-57f69");
  assert.equal(firestoreRoot({}), "https://firestore.googleapis.com/v1/projects/omni-57f69/databases/(default)/documents");
});

test("DEMO Worker resolves only its separately configured Firebase project", () => {
  const env = { OMNI_ENVIRONMENT: "DEMO", OMNI_FIREBASE_PROJECT_ID: "omni-demo-lab" };
  assert.equal(firebaseProjectId(env), "omni-demo-lab");
  assert.equal(firestoreRoot(env), "https://firestore.googleapis.com/v1/projects/omni-demo-lab/databases/(default)/documents");
});

test("DEMO Worker fails closed when project id is missing", () => {
  assert.throws(() => firebaseProjectId({ OMNI_ENVIRONMENT: "DEMO" }), /proyecto Firebase DEMO independiente/);
});

test("DEMO Worker refuses the production Firebase project", () => {
  assert.throws(() => firebaseProjectId({ OMNI_ENVIRONMENT: "DEMO", OMNI_FIREBASE_PROJECT_ID: "omni-57f69" }), /proyecto Firebase DEMO independiente/);
});

test("invalid Firebase project ids are rejected", () => {
  assert.throws(() => firebaseProjectId({ OMNI_ENVIRONMENT: "DEMO", OMNI_FIREBASE_PROJECT_ID: "not valid/id" }), /no es válido/);
});
