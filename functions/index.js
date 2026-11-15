const { onDocumentCreated, onDocumentUpdated } = require("firebase-functions/v2/firestore");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");

initializeApp();

const DATABASE_ID = "ai-studio-android-whatacal-590aa78e-55b2-46cf-aab7-6d7d406bfd66";

async function getPrivateUserInfo(db, uid) {
  const snap = await db.doc(`users/${uid}/private/info`).get();
  return snap.exists ? snap.data() : null;
}

/**
 * 1. Incoming Call Push Notification (works even when recipient app is closed)
 */
exports.notifyIncomingCall = onDocumentCreated(
  {
    document: "calls/{callId}",
    database: DATABASE_ID,
  },
  async (event) => {
    const call = event.data?.data();
    if (!call || call.status !== "ringing") return;

    const db = getFirestore(DATABASE_ID);
    const calleeInfo = await getPrivateUserInfo(db, call.calleeId);
    if (!calleeInfo?.fcmToken) return;
    if (calleeInfo.callRingtoneEnabled === false) return;
    if (Array.isArray(calleeInfo.blockedUserIds) && calleeInfo.blockedUserIds.includes(call.callerId)) return;

    await getMessaging().send({
      token: calleeInfo.fcmToken,
      android: { priority: "high" },
      data: {
        type: "incoming_call",
        callId: event.params.callId,
        callerName: String(call.callerName || "Whatacall User"),
        callType: String(call.callType || "audio"),
      },
    });
  }
);

/**
 * 2. Missed Call Push Notification
 */
exports.notifyMissedCall = onDocumentUpdated(
  {
    document: "calls/{callId}",
    database: DATABASE_ID,
  },
  async (event) => {
    const before = event.data?.before?.data();
    const after = event.data?.after?.data();
    if (!before || !after) return;
    if (before.status === after.status || after.status !== "missed") return;

    const db = getFirestore(DATABASE_ID);
    const calleeInfo = await getPrivateUserInfo(db, after.calleeId);
    if (!calleeInfo?.fcmToken || calleeInfo.notificationsEnabled === false) return;

    await getMessaging().send({
      token: calleeInfo.fcmToken,
      android: { priority: "high" },
      data: {
        type: "missed_call",
        callerName: String(after.callerName || "Whatacall User"),
        callType: String(after.callType || "audio"),
      },
    });
  }
);

/**
 * 3. New Message Push Notification (E2EE-safe: never exposes plaintext if encrypted)
 */
exports.notifyNewMessage = onDocumentCreated(
  {
    document: "chats/{chatId}/messages/{messageId}",
    database: DATABASE_ID,
  },
  async (event) => {
    const msg = event.data?.data();
    if (!msg || msg.type === "call") return;

    const db = getFirestore(DATABASE_ID);
    const chatSnap = await db.doc(`chats/${event.params.chatId}`).get();
    const chat = chatSnap.exists ? chatSnap.data() : null;
    if (!chat || !Array.isArray(chat.participantIds)) return;

    const mutedBy = Array.isArray(chat.mutedBy) ? chat.mutedBy : [];
    const preview =
      msg.encryptionVersion === 1
        ? "New encrypted message"
        : msg.type === "image"
          ? "Sent a photo"
          : msg.type === "video"
            ? "Sent a video"
            : msg.type === "audio"
              ? "Sent a voice message"
              : msg.type === "document"
                ? `Sent ${msg.mediaName || "a document"}`
                : String(msg.text || "New message").slice(0, 140);

    const recipients = chat.participantIds.filter(
      (uid) => uid !== msg.senderId && !mutedBy.includes(uid)
    );

    await Promise.all(
      recipients.map(async (uid) => {
        const info = await getPrivateUserInfo(db, uid);
        if (!info?.fcmToken || info.notificationsEnabled === false) return;
        if (Array.isArray(info.blockedUserIds) && info.blockedUserIds.includes(msg.senderId)) return;

        await getMessaging().send({
          token: info.fcmToken,
          android: { priority: "high" },
          data: {
            type: "message",
            chatId: event.params.chatId,
            senderName: String(msg.senderName || "Whatacall"),
            preview,
          },
        });
      })
    );
  }
);
