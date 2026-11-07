# LOCUS - Navigate smart. Help your community.

Java Android app (Android Studio, minSdk 26, targetSdk 34) built on **OpenStreetMap + osmdroid + OSRM**,
with Firebase Authentication / Firestore / Realtime Database and a Room + SQLite offline layer.

## 1. Run it (Android Studio + your phone)

1. Install **Android Studio** (Koala or newer, JDK 17 is bundled).
2. `File > Open` and pick the `LOCUS` folder. Wait for **Gradle Sync** to finish (needs internet the first time).
3. Phone: enable *Developer options > USB debugging*, connect it, accept the prompt.
4. Press **Run (green triangle)**. The app installs and starts.

The app works **without Firebase** straight away: tap **Create an account** - the account is then stored
securely on the phone only (salted PBKDF2 hash, never the password). Map, Explore, Navigation, hazards,
Saved Places, offline cache and the lifecycle/network labs all work. Online sign-in, shared community
requests and shared hazards need step 2.

### Build an APK
`Build > Build Bundle(s) / APK(s) > Build APK(s)` -> `app/build/outputs/apk/debug/app-debug.apk`.
Copy it to the phone and open it (allow "install unknown apps"), or `adb install app-debug.apk`.

## 2. Connect Firebase (about 10 minutes)

1. https://console.firebase.google.com -> **Add project**.
2. **Add app > Android**, package name `com.locus.app`. Download **google-services.json** and put it in `LOCUS/app/`.
3. **Build > Authentication > Sign-in method** -> enable **Email/Password**.
4. **Build > Firestore Database** -> Create database. In *Rules*, paste `firestore.rules` from this project.
5. **Build > Realtime Database** -> Create database. In *Rules*, paste `database.rules.json`.
6. Re-sync Gradle and build the APK again. Register an account in the app.
7. **Install that same APK on every phone** (all phones must use the same Firebase project) and log in - everybody then sees everybody's Community posts.

Rules are the Firebase Security Rules item of the syllabus: signed-in users can read every post; a new post must be owned by its author and time-stamped by the server; the owner can only mark it done/open or delete it. **If you installed an older version, paste the new `firestore.rules` again and press Publish.**

After step 7 open **Community > menu (three dots) > Check connection**. It writes and reads a test document on the server and tells you in plain words what is wrong (no google-services.json, not logged in online, no internet, rules not published, database not created).

## 3. Using the app (demo script)

| Screen | What to show |
|---|---|
| Splash -> Login / Register | sign-in is required every time the app is opened; strong-password rules + strength bar, wrong-password counter with lock-out, fingerprint / screen-lock unlock |
| Dashboard | online/offline banner, 4 cards, **Log out** button (with confirmation) and the three-dot button = **Settings** (Language, Lock app, Change password) |
| **Explore Places** | type ANYTHING (a shop, a service, a brand, a named area or landmark, even Bangla words; the box just says *Search anything*) -> nearby places that offer it, nearest first, with distance + walk/bike/car time; recent-search chips, pull to refresh, tap = details, star = save, arrow = navigate |
| Place details | Call / Maps app / Share (implicit intents), data passed by Intent |
| **Smart Navigation** | your GPS position is the start automatically and is followed continuously (blue dot + accuracy circle; if GPS is off or slow it says so and tells you how to fix it). **Search** or **tap the map** to choose a destination; **Back** (top-left arrow, and the phone's Back button) steps out of navigation, then leaves the screen. Choose Walk / Cycle / Car. Rain is **not chosen by you**: the program reads the weather and adjusts the arrival time. Traffic is not used. The program finds **all possible paths** (up to six) and draws them; the **shortest path is highlighted in thick green**, the others are drawn thin; **tap any other path on the map (or its chip) and that path becomes the highlighted one** (thick blue). *Start* gives turn-by-turn instructions ("In 120 m turn left onto ...") and re-routes by itself if you leave the route. Long-press the map for *Go here / Start from here / Report a hazard here* |
| Hazard alerts | everybody signed in gets a **heads-up notification** when somebody else reports a hazard within 10 km (live while the app is open, and every ~15 min through WorkManager when it is closed) and a **"Hazard cleared"** notification when it is removed. Tap a hazard marker -> **Remove hazard** (anyone signed in can clear a hazard that is gone; republish `database.rules.json` for this) |
| Hazard + reroute | with a route shown, press the orange **warning button** (reports at your position) or long-press the map -> pick *Road closed* -> the road is blocked, the graph is updated and the route is recalculated automatically |
| Live navigation | foreground service + notification keeps GPS running while navigating; the app tells you when you arrive |
| **Language** | Dashboard menu -> *Language* (also on the Login screen): English, Bangla (বাংলা) or Hindi (हिन्दी). The whole app switches at once, including place categories and messages (notifications follow it on Android 13+, older phones use the phone language for them); on Android 13+ it also appears in the phone's per-app language settings |
| **Community Assistance** | blood / volunteer / disaster / donation / travel / trekking requests, filter chips, + to create, long-press your own to mark fulfilled or delete, real-time sync |
| Saved Places | content provider CRUD: edit note, delete |
| Offline test | switch on airplane mode: banner turns red, Explore shows the Room cache, new requests/hazards are saved locally and pushed later by WorkManager |

The map needs internet for tiles and roads the first time. LOCUS downloads the roads around you in the background as soon as Navigation opens, so the first route is usually instant. Trips up to about 5 km use the local road graph (hazard-aware); longer trips are handled automatically by the online OSRM server (driving, hazards not applied).

## 4. How it maps to the flowchart

```
Launch -> Splash -> Login/Register (Firebase Auth) -> Dashboard
  Explore Places    : GPS -> QueryPlanner -> Overpass (JSON) + Nominatim (XML) in parallel -> merge -> RecyclerView  (cached in Room per search)
  Smart Navigation  : GPS start + tapped/searched destination -> Overpass roads -> weighted Graph
                      -> road conditions + hazards -> route search (algorithm chosen by the program)
                      -> animated search on the map -> route + distance/time
                      -> hazard on the route? -> update edge weights / block roads -> recalculate
  Community         : create / browse requests -> validate -> save Room + Firestore -> sync + display
  Room + Firebase sync (WorkManager) -> back to Dashboard
```

## 5. Syllabus checklist (where to find each item)

**1. Fundamentals and Activities** - Gradle files (`build.gradle`, `settings.gradle`), `AndroidManifest.xml`,
12 activities, lifecycle callbacks overridden in `MainActivity` and logged app-wide by `LifecycleLogger`
(screen *Lifecycle log*), XML layouts, **View Binding** (`buildFeatures.viewBinding`), click listeners,
explicit intents (dashboard cards), implicit intents (`ExternalIntents`: dial, geo, share, maps navigation),
data passed with extras (`PlaceDetailActivity.EXTRA_PLACE`, `NavigationActivity.intentTo`).

**2. UI and RecyclerView** - ConstraintLayout, LinearLayout, FrameLayout, ScrollView; Material 3 theme,
reusable `LocusCard` style, item layouts; `PlacesAdapter`, `RequestsAdapter` (ListAdapter + DiffUtil + ViewHolder),
`SavedPlacesActivity` (plain RecyclerView.Adapter), item clicks, swipe-to-refresh, filter chips.

**3. Components** - `LocationTrackingService` (foreground service + lifecycle logs), `HazardAlertReceiver`
(custom broadcast declared in the manifest), `SystemEventsReceiver` (system broadcasts, registered in
onStart/onStop), `FavoritesProvider` (content provider with a ContentResolver client, UriMatcher, ContentObserver),
runtime permissions (`PermissionHelper`, ActivityResult API).

**4. Networking** - `HttpClient` (HttpURLConnection, GET/POST/PUT/DELETE, HTTPS, timeouts, friendly errors),
Overpass + OSRM (JSON), Nominatim (**XML** via XmlPullParser), streaming JSON parser for the road network,
`NetworkLabActivity` (REST playground), INTERNET permission, background threads (`AppExecutors`),
loading indicators, connectivity checks.

**5. Database** - Room (`LocusDatabase`, DAOs, entities, LiveData, transactions) and raw **SQLite**
(`LocusSqlHelper`: favorites + search history, CRUD), data survives restarts, offline cache, background DB work.

**6. Firebase** - Authentication, Cloud Firestore (community requests, real-time listener), Realtime Database
(hazards, `ChildEventListener`), reads/writes/updates/deletes, security rules files, error handling, data shown
in RecyclerViews.

**7. Advanced APIs** - FusedLocation (one-shot + live), OpenStreetMap maps (osmdroid), notification channels and
notifications, WorkManager background sync, connectivity monitoring (`ConnectivityMonitor`), permissions/privacy,
external app integration, JVM unit tests (`./gradlew test`), APK build steps above.

## 6. Routing core (the algorithms part)

`graph/Graph.java` nodes = OSM nodes, edges = road segments (two directions, one-way aware); it also finds the
biggest connected network so the start/destination never snap onto an isolated lane.
`graph/CostModel.java` has two models: `CostModel.shortestPath(mode)` (edge cost proportional to length, so the cheapest path is
the SHORTEST by distance; closed roads = infinite cost, hazards add a penalty) and the time model (length / speed for Walk / Cycle /
Car by road class) used for arrival times. Rain comes from `net/WeatherApi.java` (Open-Meteo, free, no key) through
`graph/RainRules.java` and only stretches the arrival time (`CostModel.rainSlowdown`). Traffic is not used.
`Router.alternatives(...)` finds up to six different paths (penalty method: every found path makes its own roads more
expensive for the next search, near-duplicates are dropped) and the shortest of them is highlighted; `graph/RouteGuide.java` turns a route into turn-by-turn steps. `graph/Router.java` exposes one method, `Router.route(...)`: the program uses A*
(same optimal answer as Dijkstra, far fewer roads explored). Dijkstra stays in the code only so a unit test can
prove both agree. There is no algorithm or rain setting anywhere in the UI. Tests: `app/src/test/.../GraphRouterTest.java`, `NavigationLogicTest.java`.

## 7. Notes and limits

- OSM tile, Overpass, Nominatim and OSRM public servers are free but rate limited; fine for a demo, not production.
- Hazards and requests are deliberately simple (any signed-in user can read; only owners edit).
- `google-services.json` is git-ignored because it holds your project keys.
- If a Gradle sync complains about versions, use Android Studio's suggested upgrade (AGP 8.5.2, Gradle 8.7).


## How Explore Places finds "anything" (and keeps it precise)

1. **Your position.** Explore uses a live high-accuracy GPS feed (`LiveLocation`), not a one-off coarse guess. The line under
   the search box shows the accuracy ("accurate to about 20 m"). A search waits up to 9 seconds for a fix within 100 m, then
   uses the best position it has. If you walk more than 300 m the results refresh by themselves; tap that line to get a
   fresh fix, allow permission or switch location on.
2. **Understanding the words.** `QueryPlanner` turns the text into OpenStreetMap tags (synonyms such as *medicine* ->
   pharmacy, English, Bangla and Hindi words) plus a generic match on tag values and on names/brands for unknown words.
3. **Searching in rings.** `PlaceSearch` asks **Overpass**, **Nominatim** and **Photon** at the same time, first within
   1.5 km, then 4 km, 10 km and 25 km only if there are not enough good matches. Small rings never hit the servers'
   result limit, so the closest places are always complete. Duplicates from two services are merged into one record.
4. **Relevance.** Each result is scored (`Relevance`): *is that kind of place* (3) beats *has the word in its name* (2)
   beats *loose match* (1). "Best match" order = distance minus a bonus per step (1.2 km each); when there are at least
   3 real matches the loose ones are hidden. "Nearest" sorts by distance only.
5. **Opening hours** are shown only on the place's detail screen (the *Open now* filter and the list badges were removed
   because OpenStreetMap hours are often missing or out of date).
6. **Filters and map.** Chips: Best match / Nearest, 1 km / 3 km / 10 km. The map button (top-right) shows you,
   your accuracy circle and the results as pins; tap a pin for the place card.
7. **Offline / cache.** The same search in the same 300 m for 3 minutes reuses the answer; Room keeps earlier searches
   (only used if they are from around you) for offline use. Walk/bike/car times assume the road is 30% longer than the
   straight line.

Tests: `ExploreRankingTest` (opening hours, relevance, ordering) and `QueryPlannerTest`.

Limits: results come from OpenStreetMap, so a place only appears if someone has mapped it - coverage in some areas is
thin. If a word finds nothing, try a more general one (*clinic*, *repair*, *salon*). Hours are only as good as the map data.


## Authentication and app lock

| Feature | How it works |
|---|---|
| Login every time | The "unlocked" flag lives only in memory (`Session`). Closing the app, swiping it from recents, or leaving it for more than 2 minutes (`AuthGate.AUTO_LOCK_MS`) locks it; the next open shows the Login screen. Rotating the phone does not lock. |
| Welcome back | The account is remembered, so the lock screen asks only for the password, or fingerprint / face / screen lock (`BiometricPrompt`). |
| Log out | Toolbar button on the Dashboard, with a confirmation. It forgets the account on the phone, stops the tracking service and returns to Login. "Lock app" in the menu locks without logging out. |
| Strong passwords | At least 8 characters with letters and numbers, not a common password, not containing the e-mail (`PasswordRules`). |
| Brute-force protection | 5 wrong passwords lock sign-in for 30 s, doubling each further failure up to 15 min (`LockoutPolicy`). |
| Safe storage | PBKDF2-HMAC-SHA256, 100,000 iterations, random salt (`PasswordHasher`). Firebase users also get a local hash so they can unlock offline. Backups are disabled in the manifest. |
| No guest bypass | The old "Continue as guest" button was removed. |

To demo: sign in, press Back until the app closes, open it again - the Login screen appears.

## Languages

Strings live in `res/values/strings.xml` (English), `res/values-bn/strings.xml` (Bangla) and `res/values-hi/strings.xml`
(Hindi). To add a language: copy `values/strings.xml` to `values-<code>/`, translate it, add the code to
`res/xml/locales_config.xml` and to `LanguageHelper.TAGS`. The choice is stored by AppCompat
(`AppLocalesMetadataHolderService` in the manifest). Place names come in the app language when OpenStreetMap has them
(`name:bn`, `name:hi`), and Explore understands common Bangla and Hindi search words. Place categories use `cat_*`
strings; an unknown category is shown as OpenStreetMap names it. The Bangla and Hindi texts were written by an AI
assistant - have a native speaker read them before a formal submission.

## Community Assistance: how posts reach everyone

- Posts are saved on the phone first (Room) and uploaded to Cloud Firestore (`requests`). `CommunitySync` keeps ONE
  real-time listener open while any screen of the app is visible and someone is logged in, so the list always shows
  every post on the shared board - yours and everyone else's - without opening the Community screen first.
- New posts by other people raise a notification (at most 3 at a time; nothing old is announced after a reinstall).
- Posts written offline, or before the cloud was connected, upload automatically later (WorkManager) and are
  re-labelled with your cloud account so the rules accept them.
- The banner at the top of the Community screen is always honest: *Shared with everyone using LOCUS - updated 14:05*
  (green), *Offline*, *Only on this phone: cloud sharing is not set up* (tap for the steps), *rules not published*,
  or *database not created*. Without `google-services.json` the app still works, but posts stay on one phone - there is
  no way to share between phones without a cloud service.
- Filter chips: All, **My posts**, and each request type. Your own posts say "You".
- Only the owner can mark a post fulfilled or delete it (Firestore rules, `firestore.rules`).

### Reaching every phone, in three layers

| Layer | When it works | Delay | Needs |
|---|---|---|---|
| 1. Live listener (`CommunitySync`) | app open on any screen | about 1 second | Firebase project |
| 2. Background check (`CommunityCheckWorker`) | app closed, phone online | up to 15 minutes (Android's minimum) | Firebase project |
| 3. Push alert (`CommunityMessagingService` + `functions/index.js`) | app closed, phone online | a few seconds | Firebase Blaze plan (optional) |

All three feed the same Room database and the same "already announced" time stamp, so one post is announced once.
- **Posting:** a post is saved on the phone, then written to the cloud. The screen tells you which of four things
  happened: *shared* (the server confirmed), *saved - will share when online*, *refused by the board* (rules not
  published; the post stays on this phone and is not retried forever), or *only on this phone* (no cloud set up).
- **Time:** the server stamps every post (`serverTimestamp`), so a phone with a wrong clock cannot hide or jump the queue.
- **Posts written offline** upload by themselves later (WorkManager) and are never uploaded twice.
- **Optional instant push:** `cd LOCUS && firebase init functions` (keep `functions/`), then `firebase deploy --only functions`.
  Phones subscribe to the `community` topic when someone logs in and unsubscribe when they log out.

### Real-life limits (be honest in the viva)
- One Firebase project is needed; the same APK (or the same `google-services.json`) must be on every phone.
- There is no moderation or report button yet; every logged-in user can post. Add one before a public launch.
- Without layer 3, an alert can arrive up to 15 minutes late while the app is closed. Some phone makers (Xiaomi,
  Oppo, Vivo, Samsung) stop background work for apps; set LOCUS to *Unrestricted* in battery settings.
- Android 13+ asks once for the notification permission; without it posts still appear in the list, only the alert is missing.
- Posts made by an older version (numeric time stamp) are still shown correctly.

## Navigation accuracy (search, arrival time, all paths, weather)

**Destination search.** Two OpenStreetMap geocoders (Photon + Nominatim) are asked at the same time, both biased to the
start point; `GeoRank` scores how well each name matches what you typed, removes duplicates and puts the nearest good match
first (a hospital with that name in your city beats a namesake in another country). Suggestions appear while typing, typed
coordinates (`22.8456, 89.5403`) work, and if one service is down the other still answers.

**Arrival time.** Every travel mode now asks the routing server built for it (foot, bicycle, car; the old code asked the
car server for everything, so walking times were wrong on long trips). For trips on the phone's road graph the time is
calibrated against that online router's real free-flow time for the same trip (`EtaModel.calibrationScale`), then rain
is applied on top; offline it falls back to road-class speeds plus junction delays. The card shows the clock time
of arrival.

**Paths.** Traffic is not used. The program finds every sensible path between the two points (up to six, A* / Dijkstra on
the phone's road graph, each new path made different from the ones before) and draws them all on the map: the **shortest path
by distance is highlighted** (thick green with a white outline), the others are blue. Tap a blue path or its chip to look at it;
the card shows how much longer it is than the shortest one.

**Weather.** Open-Meteo gives the weather now and hour by hour. The program uses the worse of "rain now" and "rain at the
time you will ARRIVE" and stretches the arrival time of every path by it (walking and cycling slow down more than cars). The
weather chip shows what it found, and the card says "Rain slows the trip" / "Rain should stop before you arrive".

## Starting point: current location or a place you set

The pin button at the top-left of Smart Navigation (and the *From:* chip below the search bar) opens the starting-point sheet:
- **Use current location** - follows your GPS.
- **Search a start place** or **Choose on map** (move the map under the pin, press *Set start here*).
- Every place you set is **saved automatically** (SQLite table `start_places`) and listed under *Saved start places*, where it
  can be removed one by one or all at once.
The choice is remembered, so a place found in Explore and opened with the navigate arrow draws its route from the start you
chose. Turn-by-turn guidance follows your GPS, so if your set start is not where you are, the app offers to switch to your
current location when you press *Start navigation*.

## Comments under Community posts

Every post card has a **Comment** row (it shows "3 comments" when there are some). Tap it to open the comment section of that post: read what
everybody wrote and add your own. Comments are saved on the phone first (so they work offline, marked "waiting to be shared") and are shared
through the same cloud as the posts (Firestore collection `comments`, one document per comment); a live listener brings the comments of all
phones in, so they appear by themselves. Tap **Reply** under a comment to answer it: the answer appears indented under that comment with "In reply to <name>" (answering a reply stays under the same comment, so threads never get messy). Long-press a comment to delete it (the writer or the owner of the post can). **Publish the new
`firestore.rules` again** (Firebase console > Firestore Database > Rules), because it now contains the rules for comments.

## Settings and change password

The three-dot button on the dashboard opens **Settings**: Language, Lock app, Change password.
*Change password* asks for the **current password first**, then the new one twice (same rules and strength bar as
registration). Wrong current passwords count towards the same lock-out as the login screen. It works for online (Firebase)
accounts - the user is re-authenticated, then `updatePassword` is called - and for accounts stored on the phone.

**E-mail notice.** An app cannot send e-mail by itself, so a free service (EmailJS, 200 mails/month) is used and no server is
needed:
1. Create an account at emailjs.com, add an e-mail service (Gmail), create a template whose *To* field is `{{to_email}}` and
   whose body uses `{{to_name}}`, `{{time}}`, `{{message}}`.
2. Account > Security: allow API calls from non-browser applications.
3. Add three lines to `LOCUS/local.properties` and rebuild:
   `EMAILJS_SERVICE_ID=...`, `EMAILJS_TEMPLATE_ID=...`, `EMAILJS_PUBLIC_KEY=...`
Without these the password still changes and the screen says that no e-mail was sent. (A Cloud Function would be stronger
because the mail is sent from a server, but it needs the paid Firebase plan.)

Note: the *Lifecycle log* and *Network lab* screens were removed from the menu; their code is still in the project
(`LifecycleLogActivity`, `NetworkLabActivity`) for the syllabus checklist.

**Forgot password (login page and Settings > Change password)** works like Facebook / Instagram: tap "Forgot password?", type your e-mail,
tap "Send reset link". Firebase e-mails a link; open it, choose a new password, log in with it. After a normal password change the screen only
says "Password changed successfully."

**Connect Firebase without rebuilding (Settings > Cloud account, or the button that appears when something needs Firebase).** Download
`google-services.json` from your Firebase project (steps are on that screen), copy it to the phone, tap "Choose google-services.json". It is kept
on the phone and Firebase starts with it every time. (If the file is already in the `app/` folder at build time, that one is used instead.) An account
made before Firebase was connected is moved to Firebase automatically the next time you log in with its e-mail and password, so reset e-mails work for it too.
In the Firebase console you must have turned on **Authentication > Email/Password**; the reset e-mail may land in Spam the first time.

**Lock page and Community.** The lock page shows only the password / fingerprint box (no language link, no cloud notice). The Community screen shows no "only on this phone" banner when cloud sharing is not set up; the other sync messages (live, offline, errors) still appear.

## E-mail set-up inside the app (no rebuild)

Open **Settings -> Cloud account -> Connect Firebase** (the same screen where you choose `google-services.json`). Scroll to **E-mail notice after a password change**, paste the EmailJS *Service ID*, *Template ID* and *Public Key*, tap **Save**, then **Send test e-mail**. The result line shows the real answer from EmailJS, for example *"The Public Key is invalid"*, *"Template ID not found"* or *"API calls are disabled for non-browser applications"* (turn on **Account -> Security -> Allow EmailJS API for non-browser applications**). Template: To Email = `{{to_email}}`, content uses `{{message}}` and `{{time}}`.

## Forgot password - why no mail may arrive

Firebase answers "sent" even for an e-mail it does not know (a privacy rule against probing for accounts). An account created **before** Firebase was connected exists only on the phone. Fix: connect Firebase, **log in once with the current password while online** (LOCUS then creates the account in Firebase), and use *Forgot password?* again. Also check Spam, and that **Authentication -> Sign-in method -> Email/Password** is enabled.

## Hazard alerts - checklist

1. Realtime Database -> Rules: paste `database.rules.json` and **Publish** (this lets anyone signed in remove a hazard).
2. Allow notifications for LOCUS when Android asks (Android 13+).
3. Alerts need the hazard to be new (< 1 hour), within 10 km of your last known position, and reported by someone else.
