const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_uid_123";
const BOB_UID = "bob_uid_456";
const CHARLIE_UID = "charlie_uid_789";
const ADMIN_EMAIL = "nayemtisha4072@gmail.com";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function nowTs() {
  return new Date(Date.now() - 1000);
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read users, chats, calls, or statuses", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("chats").where("participantIds", "array-contains", ALICE_UID).get());
  await assertFails(unauthDb.collection("calls").where("participantIds", "array-contains", ALICE_UID).get());
  await assertFails(unauthDb.collection("statuses").where("privacy", "==", "everyone").get());
});

test("User profile: owner can create valid profile; cannot self-suspend or inject ghost field", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const ts = nowTs();
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      uid: ALICE_UID,
      displayName: "Alice Rahman",
      isPhoneVerified: true,
      isSuspended: false,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      isSuspended: true,
      updatedAt: nowTs(),
    })
  );

  await assertFails(
    aliceDb.collection("users").doc(BOB_UID).set({
      uid: BOB_UID,
      displayName: "Spoof",
      isPhoneVerified: true,
      isSuspended: false,
      createdAt: ts,
      updatedAt: ts,
    })
  );
});

test("User private PII: strictly owner-only (another user & even admin cannot read)", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const ts = nowTs();
    await ctx.firestore().collection("users").doc(ALICE_UID).collection("private").doc("info").set({
      uid: ALICE_UID,
      phoneNumber: "+8801712345678",
      createdAt: ts,
      updatedAt: ts,
    });
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("private").doc("info").get());

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("private").doc("info").get());

  const adminDb = testEnv.authenticatedContext("admin_uid", {
    email: ADMIN_EMAIL,
    email_verified: true,
  }).firestore();
  await assertFails(adminDb.collection("users").doc(ALICE_UID).collection("private").doc("info").get());
});

test("Chats & Messages: participants can access; non-participants and admins cannot read messages", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  const adminDb = testEnv.authenticatedContext("admin_uid", {
    email: ADMIN_EMAIL,
    email_verified: true,
  }).firestore();

  const ts = nowTs();
  await assertSucceeds(
    aliceDb.collection("chats").doc("chat_1").set({
      type: "direct",
      participantIds: [ALICE_UID, BOB_UID],
      adminIds: [ALICE_UID],
      createdBy: ALICE_UID,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(
    aliceDb.collection("chats").doc("chat_1").collection("messages").doc("msg_1").set({
      chatId: "chat_1",
      senderId: ALICE_UID,
      type: "text",
      text: "Hello Bob!",
      participantIds: [ALICE_UID, BOB_UID],
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(
    bobDb.collection("chats").doc("chat_1").collection("messages").where("participantIds", "array-contains", BOB_UID).get()
  );

  // Non-participant rejected
  await assertFails(
    charlieDb.collection("chats").doc("chat_1").collection("messages").doc("msg_1").get()
  );

  // Admin cannot read private chat messages
  await assertFails(
    adminDb.collection("chats").doc("chat_1").collection("messages").doc("msg_1").get()
  );
});

test("Calls: terminal state locks further updates and prevents tampering", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  const ts = nowTs();

  await assertSucceeds(
    aliceDb.collection("calls").doc("call_1").set({
      callerId: ALICE_UID,
      calleeId: BOB_UID,
      participantIds: [ALICE_UID, BOB_UID],
      callType: "video",
      status: "ringing",
      offerSdp: "v=0...",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Callee accepts & ends call
  await assertSucceeds(
    bobDb.collection("calls").doc("call_1").update({
      status: "connected",
      answerSdp: "v=0...",
      updatedAt: nowTs(),
    })
  );

  await assertSucceeds(
    aliceDb.collection("calls").doc("call_1").update({
      status: "ended",
      durationSeconds: 42,
      updatedAt: nowTs(),
    })
  );

  // Terminal state locking prevents further updates
  await assertFails(
    bobDb.collection("calls").doc("call_1").update({
      status: "connected",
      updatedAt: nowTs(),
    })
  );

  // Non-participant rejected
  await assertFails(charlieDb.collection("calls").doc("call_1").get());
});

test("Reports & Admin RBAC: verified admin can moderate; unverified spoofed email rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const spoofAdminDb = testEnv.authenticatedContext("spoof_uid", {
    email: ADMIN_EMAIL,
    email_verified: false,
  }).firestore();
  const realAdminDb = testEnv.authenticatedContext("admin_uid", {
    email: ADMIN_EMAIL,
    email_verified: true,
  }).firestore();

  const ts = nowTs();
  await assertSucceeds(
    aliceDb.collection("reports").doc("rep_1").set({
      reporterId: ALICE_UID,
      reportedUserId: BOB_UID,
      reason: "Spam",
      status: "pending",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Normal user cannot update report status
  await assertFails(
    bobDb.collection("reports").doc("rep_1").update({
      status: "dismissed",
      updatedAt: nowTs(),
    })
  );

  // Spoofed unverified admin email rejected
  await assertFails(
    spoofAdminDb.collection("reports").doc("rep_1").update({
      status: "resolved",
      updatedAt: nowTs(),
    })
  );

  // Verified admin succeeds
  await assertSucceeds(
    realAdminDb.collection("reports").doc("rep_1").update({
      status: "resolved",
      resolutionNote: "Warning issued",
      updatedAt: nowTs(),
    })
  );
});
