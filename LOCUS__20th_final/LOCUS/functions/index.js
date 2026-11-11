// OPTIONAL. Makes new Community posts appear on every phone within seconds, even when LOCUS is closed.
// Without it the app still works: phones get posts live while the app is open, and every 15 minutes in the background.
//
// Deploy (needs the Firebase "Blaze" pay-as-you-go plan; a small class project stays inside the free quota):
//   npm install -g firebase-tools && firebase login
//   cd LOCUS && firebase init functions   (choose your project, JavaScript, keep this folder)
//   firebase deploy --only functions
const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { onValueCreated } = require("firebase-functions/v2/database");
const { getDatabase } = require("firebase-admin/database");
const { initializeApp } = require("firebase-admin/app");
const { getMessaging } = require("firebase-admin/messaging");

initializeApp();

exports.announceCommunityPost = onDocumentCreated("requests/{id}", async (event) => {
  const post = event.data && event.data.data();
  if (!post) return;
  // Data-only message: the app builds the notification itself (translated, de-duplicated, skips your own posts).
  await getMessaging().send({
    topic: "community",
    data: {
      id: String(event.params.id),
      type: String(post.type || ""),
      title: String(post.title || "").slice(0, 120),
      ownerUid: String(post.ownerUid || ""),
      createdAt: String(post.createdAt && post.createdAt.toMillis ? post.createdAt.toMillis() : Date.now()),
    },
    android: { priority: "high", ttl: 3600 * 1000 },
  });
});

// Private chat: tell the other person about a new message, even when LOCUS is closed.
// Each phone listens to the topic "chat_<its own uid>" (see CommunitySync.chatTopic).
exports.announceChatMessage = onValueCreated("/chats/{chatId}/messages/{msgId}", async (event) => {
  const msg = event.data && event.data.val();
  if (!msg || !msg.from) return;
  const chat = (await getDatabase().ref("chats/" + event.params.chatId).get()).val();
  if (!chat) return;
  const toUid = msg.from === chat.requesterUid ? chat.helperUid : chat.requesterUid;
  const fromName = msg.from === chat.requesterUid ? chat.requesterName : chat.helperName;
  const topic = "chat_" + String(toUid).replace(/[^a-zA-Z0-9-_.~%]/g, "_");
  await getMessaging().send({
    topic,
    data: {
      kind: "chat",
      chatId: String(event.params.chatId),
      toUid: String(toUid),
      fromUid: String(msg.from),
      fromName: String(fromName || ""),
      title: String(chat.requestTitle || "").slice(0, 120),
      text: msg.img ? "\uD83D\uDCF7" : String(msg.text || "").slice(0, 120),
    },
    android: { priority: "high", ttl: 3600 * 1000 },
  });
});
