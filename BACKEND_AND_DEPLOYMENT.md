# Whatacall — Backend, Firebase Console & Build Guide

## 1. Provisioned Firebase Project & Database
- **Firebase Project ID**: `gen-lang-client-0671277430`
- **Firestore Named Database ID**: `ai-studio-android-whatacal-590aa78e-55b2-46cf-aab7-6d7d406bfd66`
- **Android Application ID**: `com.aistudio.whatacall.k8m4px`
- **Debug SHA-1 Registered**: `9E:40:C6:43:F2:4E:4C:BE:A3:F4:77:AF:69:47:D1:3D:91:E3:8F:FB`

Firestore security rules (`firestore.rules`) have been validated against both the JavaScript rules test suite (`firestore.test.js`) and Robolectric repository integration tests (`WhatacallRepositoryRuleTest`) and deployed to the named database.

---

## 2. Firebase Console Steps Required Before Production Release

Because AI Studio provisioning covers **Firestore Enterprise** and **Google Sign-In**, enable these additional Firebase Console products on project `gen-lang-client-0671277430`:

1. **Firebase Phone Authentication**:
   - Open **Firebase Console -> Authentication -> Sign-in method -> Add new provider -> Phone** and enable it.
   - Verify SHA-1 (`9E:40:C6:43:F2:4E:4C:BE:A3:F4:77:AF:69:47:D1:3D:91:E3:8F:FB`) and your release keystore SHA-1 / SHA-256 are listed under **Project Settings -> Your Android App** (required for Play Integrity / Silent SMS verification; otherwise Firebase falls back to reCAPTCHA).
   - For automated QA without SMS charges, you can add test phone numbers (e.g. `+8801700000000` -> `123456`) under **Phone numbers for testing**.

2. **Firebase Storage**:
   - Open **Firebase Console -> Storage -> Get started** to initialize the default Cloud Storage bucket.
   - Deploy `storage.rules` from the workspace root (`firebase deploy --only storage` or paste via the Console Rules editor).

3. **Firebase Cloud Messaging (FCM) for Background Call & Chat Notifications**:
   - The Android client already saves each user's FCM token to `/users/{uid}/private/info` (`fcmToken`) and handles high-priority data payloads in `WhatacallMessagingService`.
   - Deploy the Cloud Function below (Node.js 20, `firebase-functions/v2` + `firebase-admin`) so Firestore call creations and chat messages trigger FCM pushes when the recipient app is closed or backgrounded:

```javascript
const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");

initializeApp();
const DATABASE_ID = "ai-studio-android-whatacal-590aa78e-55b2-46cf-aab7-6d7d406bfd66";

exports.notifyIncomingCall = onDocumentCreated(
  {
    document: "calls/{callId}",
    database: DATABASE_ID,
  },
  async (event) => {
    const call = event.data?.data();
    if (!call || call.status !== "ringing") return;

    const db = getFirestore(DATABASE_ID);
    const privateSnap = await db.doc(`users/${call.calleeId}/private/info`).get();
    const fcmToken = privateSnap.data()?.fcmToken;
    if (!fcmToken) return;

    await getMessaging().send({
      token: fcmToken,
      android: { priority: "high" },
      data: {
        type: "incoming_call",
        callId: event.params.callId,
        callerName: call.callerName || "Whatacall User",
        callType: call.callType || "audio",
      },
    });
  }
);
```

---

## 3. WebRTC STUN & TURN Relay Configuration

`WebRtcCallClient` configures Google public STUN servers (`stun:stun.l.google.com:19302`) out of the box and supports any RFC 5766 / 8656 TURN server (such as **coturn**, **Metered**, or **Twilio NTS**) via AI Studio Secrets:

1. Open the **Secrets panel** in AI Studio (or `.env` locally — never commit `.env`).
2. Set:
   - `TURN_SERVER_URL` (e.g. `turns:turn.yourdomain.com:5349?transport=tcp` or `turn:turn.yourdomain.com:3478`)
   - `TURN_USERNAME`
   - `TURN_CREDENTIAL`

---

## 4. Building & Generating APK / AAB

Run from the workspace root using `gradle` (do not use `./gradlew` in AI Studio's build environment):

```bash
# Compile & assemble Debug APK (signed with debugConfig)
gradle assembleDebug

# Run security rules unit tests (requires local Firebase emulators on 8085/9099)
FIRESTORE_EMULATOR_HOST="127.0.0.1:8085" node --test firestore.test.js
gradle :app:testDebugUnitTest --tests '*Rule*'

# Assemble Release APK / Android App Bundle (requires KEYSTORE_PATH, STORE_PASSWORD, KEY_PASSWORD env vars)
gradle assembleRelease
gradle bundleRelease
```
