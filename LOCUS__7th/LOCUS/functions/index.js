// OPTIONAL. Makes new Community posts appear on every phone within seconds, even when LOCUS is closed.
// Without it the app still works: phones get posts live while the app is open, and every 15 minutes in the background.
//
// Deploy (needs the Firebase "Blaze" pay-as-you-go plan; a small class project stays inside the free quota):
//   npm install -g firebase-tools && firebase login
//   cd LOCUS && firebase init functions   (choose your project, JavaScript, keep this folder)
//   firebase deploy --only functions
const { onDocumentCreated } = require("firebase-functions/v2/firestore");
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
