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
6. Re-sync Gradle and run again. Register an account in the app.

Rules are the Firebase Security Rules item of the syllabus: signed-in users can read; only the owner can edit or delete.

## 3. Using the app (demo script)

| Screen | What to show |
|---|---|
| Splash -> Login / Register | sign-in is required every time the app is opened; strong-password rules + strength bar, wrong-password counter with lock-out, fingerprint / screen-lock unlock |
| Dashboard | online/offline banner, 4 cards, **Log out** button (with confirmation) and menu (Lock app, Lifecycle log, Network lab) |
| **Explore Places** | type ANYTHING (pizza, plumber, laptop repair, a shop or brand name, even Bangla words) -> nearby places that offer it, nearest first, with distance + walk/bike/car time; recent-search chips, pull to refresh, tap = details, star = save, arrow = navigate |
| Place details | Call / Maps app / Share (implicit intents), data passed by Intent |
| **Smart Navigation** | the start is your GPS position automatically; **search** (results appear right under the box) or **tap the map** and the route appears by itself. Choose Walk / Cycle / Car and the Rain / Traffic chips - the route updates instantly. You never choose an algorithm: the program picks it. A short animation shows the roads being explored, then the route, time and distance. Long-press the map for *Go here / Start from here / Report a hazard here* |
| Hazard + reroute | with a route shown, press the orange **warning button** (reports at your position) or long-press the map -> pick *Road closed* -> the road is blocked, the graph is updated and the route is recalculated automatically ("Rerouted around the hazard, +350 m") |
| Live GPS | switch on *Live GPS tracking* (foreground service + notification + custom broadcast). If you leave the route the app recalculates by itself; near the destination it tells you that you arrived |
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
`graph/CostModel.java` edge cost = length / speed for Walk / Cycle / Car, x rain, x traffic, x hazard penalty;
closed roads = infinite cost. `graph/Router.java` exposes one method, `Router.route(...)`: the program uses A*
(same optimal answer as Dijkstra, far fewer roads explored). Dijkstra stays in the code only so a unit test can
prove both agree. There is no algorithm setting anywhere in the UI. Tests: `app/src/test/.../GraphRouterTest.java`.

## 7. Notes and limits

- OSM tile, Overpass, Nominatim and OSRM public servers are free but rate limited; fine for a demo, not production.
- Hazards and requests are deliberately simple (any signed-in user can read; only owners edit).
- `google-services.json` is git-ignored because it holds your project keys.
- If a Gradle sync complains about versions, use Android Studio's suggested upgrade (AGP 8.5.2, Gradle 8.7).


## How Explore Places finds "anything"

1. The text is cleaned and understood by `QueryPlanner` (synonym dictionary such as *medicine* -> pharmacy,
   plus a generic match on OpenStreetMap tags and on place names/brands for words it has never seen).
2. `PlaceSearch` asks **Overpass** (tags) and **Nominatim** (free text) at the same time and merges the
   answers, removing duplicates. Several public servers are tried automatically if one is busy.
3. If fewer than a few places are found within 2 km, the area widens by itself (6 km, then 15 km).
4. Results are cached in Room per search, so repeating a search shows instantly, even offline.

Limits: results come from OpenStreetMap, so a place only appears if someone has mapped it. If a word finds
nothing, try a more general one (*clinic*, *repair*, *salon*).


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
