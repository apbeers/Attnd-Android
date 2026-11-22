# Android ↔ iOS parity plan (Nov 22, 2026)

The owner asked for this: "Bring the Android app all the way up so it works with the same type of architecture as the
iPhone app, and also works on foldables, and every library and dependency is up to date."

This is a plan only; no app code changed with it. Phase 0 (toolchain and dependency upgrades, minSdk 35 → 36) runs
separately. Phases 1–12 below start from its result.

**Rule for everything here (owner, Nov 22, 2026):**
- Where Android can do what iOS does, follow iOS's choice, removals included.
- Where Android can't (the App Clip, for example), leave the feature out. Don't build an Android substitute.

The decisions this rule settles are listed in §4.

Sources read:
- the iOS `CLAUDE.md` and the iOS sources it names (`Attnd/*.swift`, `AttndTests/*.swift`);
- the Android `CLAUDE.md` and `app/src/**`;
- attnd-web: `API_V2.md`, `API_CHANGES.md`, `ROADMAP.md`, `LOG_EVENTS.md`, `docs/specs/*.md`.

**Missing spec:** `ios-my-schedule.md` (cited by the iOS `CLAUDE.md` and attnd-web `ROADMAP.md` as "owner-approved")
isn't in any of the three repos or their git history. Use these instead, in this order:
1. `Attnd-iOS/Attnd/MySchedule.swift` with its header comment;
2. `AttndTests/MyScheduleTests.swift`;
3. the iOS `CLAUDE.md` section "My schedule".

Ask the lead for the file if one exists.

Paths below are relative to each repo:
- **A** = `Attnd-Android/app/src/main/java/com/arcu/attnd/`, **AT** = `Attnd-Android/app/src/test/java/com/arcu/attnd/unit/`;
- **I** = `Attnd-iOS/Attnd/`, **IT** = `Attnd-iOS/AttndTests/`.

---

## 1. Feature-by-feature gap table

**Size:** S < 1 day · M 1–3 days · L 3–6 days · XL > 6 days, for one agent including tests.

### 1.1 Personal data: My schedule

| Feature | iOS | Android today | Gap | Android approach |
|---|---|---|---|---|
| **My schedule** replaces likes and the stored plan (items, places at a time with default 45 min, saved places without a time; reminder per entry; organizer-change fingerprints) | I`MySchedule.swift`, `EventPersonalState` in I`Glance.swift`, I`AppState.swift` § "My schedule", IT`MyScheduleTests.swift` | Hearts: A`domain/Hearts.kt`, A`data/HeartStore.kt`. Separate plan: A`domain/Planner.kt`, A`data/PlanStore.kt`, `DevicePrefs.hearts_`/`plan_` | Android has no My schedule at all | New A`domain/MySchedule.kt`, a 1:1 port of the iOS rules: add / remove / move / setReminder → `MyScheduleChange` with the API-009 metric only when membership changes; rows, sectionRows, upNext, clashes, suggestedTimes, organizerChanges, `apply(plan:replacing:)`, `reconcile`, `mealsCovered`, `placeIds`.<br>New A`data/ScheduleStore.kt` per event in `DevicePrefs`; its hearted sets mirror the entries, so search's Liked list, map filters and the planner keep working.<br>Port all 8 `MyScheduleTests` cases. |
| Migration from hearts + plan, once per event | `MySchedule.migrate`, `AppState.migrateSchedule` | — | Missing | At bundle load: hearted items + the plan's items → item entries, hearted places → saved places, the plan's meal breaks with a food place → timed place entries.<br>Old keys stay readable. No metrics. Log `storage.prefs.migrated`, which is already in `LOG_EVENTS.md`. |
| Heart UI: outline heart adds at once with an Undo toast; filled heart opens a menu (Set a time… / Remind me / Remove); "In my schedule" wording; no "like" wording anywhere | `HeartButton` in I`GlanceViews.swift`, `addToScheduleWithUndo`, `UndoToastOverlay` | Plain heart toggle: `HeartButton` in A`ui/components/Glance.kt`; "Liked" wording | Behaviour and wording differ | Material 3 `IconToggleButton`, then a `DropdownMenu` on the filled state.<br>Undo through a `SnackbarHost` with an action, placed by the Undo toast pane rule in §3.<br>Rename user-facing text the way iOS did: planner reasons "In your schedule" / "Because X is in your schedule" / "Picked by 3 of you" / "Similar to X" (I`Planner.swift` ~l.757). |
| Schedule tab "My Schedule" + "Saved Places" sections, organizer changes on top (Keep / Remove / Find another); Places / Map **My Schedule** chip (places + places of items) | I`ScheduleView.swift`, I`MapView.swift`, `MySchedule.placeIds` | Liked chip on Schedule / Places / Map | Missing | A`ui/screens/ScheduleScreen.kt` sections from `sectionRows`.<br>No Liked chip on Schedule. Places and Map get a "My Schedule" chip. |
| Reminders per entry (5 / 10 / 15 / 30 / 60 min / off, default 15), timed places too, ≤ 40 | `ReminderPlanner.reminders(leadMinutes:places:)` in I`LiveFollowLogic.swift` | One fixed rule: `LiveFollow.reminders` in A`domain/LiveFollow.kt`; `PlanStore.reminders` on/off | Per-entry lead time and place reminders missing | Port `ReminderPlanner` into `LiveFollow.kt` or a new `Reminders.kt`.<br>`LiveUpdateController` schedules from the entries. Place reminder texts follow iOS: "Taco Row in 15 min · 3 min walk", "In your schedule · 7:00 PM". |
| Share my schedule (API-015 `/p/` link of My schedule's items, QR) | `AssistantController.shareLink("schedule")`, I`Components/ShareCard.swift` | Plan share only: A`domain/PlanShare.kt`, `QrImage` | Small | Reuse `PlanShare.planLink` with the schedule's item ids; the in-house QR (A`domain/QrCode.kt`) stays. |

### 1.2 Navigation, Home and server-driven screens

| Feature | iOS | Android today | Gap | Android approach |
|---|---|---|---|---|
| **Tab layout**: the event's own tabs (`display.tabs`) with **Home always first and the default**, ≤ 5 tabs. Over 5: drop Flyer first, then Places when the Map is there, then the last tab that isn't Updates. No My plan or Explore tab. "Places" title; screenshot mode keeps the old tabs and "Locations". | I`TabLayout.swift`, IT`TabLayoutTests.swift` (11 cases) | My plan + Explore (Map \| Schedule \| Places): `EventTabModel` in A`domain/PlanTab.kt`, A`ui/AttndApp.kt`, `DevicePrefs.exploreView` | Different model | Port `TabLayout` into A`domain/TabLayout.kt` with the iOS tests. Delete Explore, `exploreView` and the My plan tab.<br>`NavigationSuiteScaffold` keeps drawing the bar or rail.<br>`legacy` screenshot tabs stay. |
| Options placement: on one tab's root only (Home's header row, else Updates, else the first tab); never on pushed details | `TabLayout.optionsTab`, `optionsToolbarItem(for:)` in I`ContentView.swift` | Options action in `EventTopBar` on every tab: A`ui/screens/SearchScreen.kt`, A`ui/AttndApp.kt` | Differs | Gear only on `optionsTab`'s root. On Home it's in the header section (Home has no top app bar, as on iOS). Options opens as a full-height `ModalBottomSheet` or full screen. |
| **Home tab** (API-024 `home` screen: header, urgent, update_banner, countdown, my_schedule, whats_on, map, places, updates, faq, getting_there, about, links, flyer, website) | I`ScreenLayoutViews.swift` (`HomeTab`), I`ScreenLayout.swift` | — | Missing | New A`ui/screens/HomeScreen.kt`: a `LazyColumn` of sections drawn by the component renderer (next row). |
| **Server-driven screens**: send `X-Attnd-Sections: 2`; per screen, ordered sections from the closed catalogue; overrides by `category:` / `tag:`; closed `when` conditions; facts; built-in defaults = today's screens; drop unknowns; ≤ 24 sections, one of each type; screenshot mode always built-in | I`ScreenLayout.swift` (`LayoutScreen`, `SectionCatalogue` v2, `ScreenLayout.resolve`, `ScreenFacts`, `TabListLayout`, `UpdatesLayout`, `OnboardingLayout`, `LiveActivitySettings`), IT`ScreenLayoutTests.swift` (37 cases, the same as attnd-web `events/tests_layout.py`) | Not sent, not decoded (A`data/api/AttndApi.kt` `bundle()` sends `If-None-Match` + `X-Device-Id` only) | Missing (API-024 and API-027 unticked for Android) | Port to A`domain/ScreenLayout.kt` with all 37 test cases.<br>Keep the raw `layout` as a `JsonElement` in `BundleDto`, then map it in `ApiMapper`.<br>Header `X-Attnd-Sections: 2`. The ETag changes (`-s2`), so the first fetch after the update is a 200.<br>Draw from it: item / place detail, event info, Options' event rows, map overlay (on/off), Home, `schedule_tab`, `places_tab`, `updates_tab`, `onboarding`. |
| **One component catalogue**: chat blocks + layout sections as one `Component` type; one renderer; aliases `plan_today` / `liked` → `my_schedule` | I`Component.swift`, IT`ComponentTests.swift`, `ComponentView` in I`AssistantBlockView.swift`, `ScreenComponentView` | Chat blocks only: A`domain/AssistantBlocks.kt`, A`ui/assistant/AssistantBlocksUi.kt` (one composable per block) | Layout sections and the shared renderer missing | A`domain/Component.kt` (port + tests). `AssistantBlock.Component(component)` for screen types in chat.<br>`ComponentView(component, surface)` in A`ui/components/ComponentView.kt` dispatches to the existing block composables and the new section composables.<br>The Home look is the reference style. |
| **API-018 context** on detail screens: performers, item facts (price / ages / accessibility), links; place hours, facts, menu, links; event FAQ, amenities with their place, text sections | I`ContextViews.swift`, `EventContext` / `LocationContext` / `ScheduleItemContext` in I`Models.swift`, IT`ContextDecodingTests.swift` | Not decoded (A`data/api/ApiDtos.kt` has no `context`) | Missing (API-018 unticked) | Lenient DTOs (every field nullable; lossy lists; https-only links; unknown amenity kind → `other`; empty → null). Port the 7 `ContextDecodingTests` cases.<br>Draw as layout sections (`performers`, `good_to_know`, `menu`, `hours`, `links`, `faq`, `amenities`…).<br>Feed `AssistantModel.retrieve` and the search weights: performers like tags, bios like descriptions. |
| **API-030 Apple Music**: "Listen on Apple Music" on performer rows (https `music.apple.com` or a subdomain only) | `ListenOnAppleMusicButton` | — | Missing | **Show it** on Android. An https link opens the Apple Music app if installed, else the browser. Use `Intent.ACTION_VIEW` + `CATEGORY_BROWSABLE`, the same as the Website button (`WebLinks.httpsUrl` + a host check). |
| **API-022 / API-031 photo credit**: `image_credit`, `image_attribution_url`; live Google photos (`/img/places/<id>/`) never prefetched or written to the disk cache; credit over the hero's corner, authors linked once the JSON loads | `PhotoCreditLine`, `PhotoAttribution`, `MediaCache.isLivePlacePhoto`, `ImageStore.data(for:)` in I`AppState.swift` | Not decoded. A`domain/ImageCachePlan.kt` would prefetch every URL, which Google's terms forbid for these | Missing | Decode both keys.<br>`ImageCachePlan.referencedUrls` skips live place photos (path `/img/places/<id>/` on our host).<br>`ImageLoader` loads them memory-only, honouring `max-age` through `HttpURLConnection`'s cache (an `HttpResponseCache` for that path only, or our own 1 h memory TTL).<br>Credit line on the place detail hero. |

### 1.3 The assistant (My plan)

| Feature | iOS | Android today | Gap | Android approach |
|---|---|---|---|---|
| **My plan = one continuous chat**: Return sends (no live results); right bubble; a working line ("Searching…", "Adding Taco Row…") that becomes a done line; components inline; follow-up chips; proposal card (Apply per option / Cancel), then an Undo card; history ≤ 40 turns per event; "Your Day" (`my_day`) before anything is asked | `PlanChatView` in I`AssistantView.swift`, I`AssistantController.swift`, `ChatTranscript` in I`AssistantFlow.swift` | A`ui/assistant/PlanTab.kt` (`MyPlanTab`, dock, answer card with the last turn only, live search while typing); `AssistantTranscript` unused | Different UI model | New A`ui/assistant/PlanChat.kt`: a full-screen destination opened from a floating **My plan** FAB (only with the model).<br>`LazyColumn` with `reverseLayout`-style bottom anchoring; composer + chips in `Scaffold(bottomBar)` with `imePadding`.<br>Retire `MyPlanTab`, the dock and the answer card. Port `ChatTranscript`. |
| **Conversation memory**: "it" / "them" / "the second one" / "option 3" / "the taco place" / "there" | I`ConversationContext.swift`, IT`ConversationContextTests.swift` (11) | — | Missing | Port to A`domain/ConversationContext.kt`. Store it with `AssistantConversation` as `{"turns":[…],"memory":{…}}`, still reading the old array. |
| **Router My schedule grammar**: add / put / save / book; remove / drop; move; remind me; what's on my schedule; show on map; directions; find; clash question with Replace / Keep both chips; app settings (location, leave event, switch server; always a confirm chip) | `AssistantRouter.scheduleCommand` in I`AssistantRouter.swift`, IT`AssistantRouterTests.swift` | A`domain/AssistantRouter.kt` (29 rules, `like` / `unlike`) | Divergent | Port the iOS rules and actions `schedule_add` / `_remove` / `_move` / `_reminder` and the read `my_schedule` into A`domain/AssistantActions.kt`.<br>Re-sync A`AT/AssistantRouterTest.kt` to the iOS JSON, including the divergences the iOS `CLAUDE.md` lists under Running Tests. |
| **Model path: tools, token budget, components over text, no streaming**: one session per conversation; `TokenBudget` (4096 window, 1300 instructions, 600 reply, chars ÷ 3.5) trims the oldest turns → items → facts; tools find / event_info / my_schedule / now_next / add / remove / move / set_reminder / plan_day / show; the model writes only the answer text (`AssistantModel.assemble`, `answerText`, `namedEntities`); any failure → the router | I`EventAssistant.swift`, I`AssistantModel.swift` (`TokenBudget`, `BudgetedPrompt`, `assemble`) | A`data/OnDeviceAssistant.kt`: stateless `generateContent` / `generateContentStream`, block-JSON replies parsed by `AssistantModel.parse`, streamed | No tools, no budget; streams block JSON | ML Kit's Prompt API has function calling, but only through the **alpha** `genai-schema` artifact (Google's page cites `genai-prompt:1.0.0-beta5` + `genai-schema:1.0.0-alpha2`).<br>**Recommendation:** our own JSON tool protocol on plain `generateContent`. The model answers `{"tool":"find","args":{…}}` or `{"answer":"…","show":[…]}`; up to 4 tool rounds; the conversation is re-sent within the budget each round (no server-side session). It's deterministic and unit-testable.<br>Port `TokenBudget` with Gemini Nano's numbers: input < 4,000 tokens per Google's docs; verify on the current beta and use `countTokens` if exposed.<br>Tool writes go through `ActionRegistry.check` → `ActionDispatcher`, as on iOS.<br>No streaming: show the working line, then the whole answer. |
| **Sequences** ("music, food, then more music") → one proposal | `PlanProposals.sequenceSteps` / `sequence`, `SwapCommand` in I`PlanProposal.swift`, IT`PlanProposalTests.swift` | A`domain/PlanTab.kt` (`PlanProposals`, `ProposalReply`, `PreviewRows`) | Sequences, swap command and the iOS case changes missing | Port the additions; re-sync A`AT/PlanTabTest.kt` to `PlanProposalTests` ("more bbq"). Port `PlanDockTests` (`addToPlan` / `planAround` / `summaryLine`). |
| **Assistant only with the on-device model**: no model → no My plan button, planner, assistant, onboarding plan card or "Ask about this"; the plain search FAB + search sheet instead | `AppState.hasAssistant` / `modelReady`, `refreshOnDeviceModel()` | Planner and router-only tab on every phone (`PlanTabFlow.withoutModel`) | Android shows more than iOS | Follow iOS: `PlanUiState.available = model AVAILABLE && !screenshot`, re-checked on every resume.<br>Without it: `SearchFab` opens a search sheet (A`ui/screens/SearchScreen.kt` grows into iOS's `EventSearchSheet`; a question with no match searches its keywords).<br>Download handling: see §4.2. |
| API-021 `plan_suggestions` | Not built on iOS either (no reference in I`*.swift`) | — | Neither app | Optional (phase 12). |

### 1.4 Look, status and banners

| Feature | iOS | Android today | Gap | Android approach |
|---|---|---|---|---|
| **API-023 themes**: `event.theme` light/dark × primary / secondary / tertiary, preferred over `accent_color`; screenshot mode keeps the brand purple; the Live Activity uses the theme | I`ThemeColors.swift` (`ThemePalette`), `LiveThemeMapping` in I`LiveFollowLogic.swift` | `accent_color` → `primary` only (A`ui/theme/Theme.kt`, A`ui/theme/Color.kt`) | Missing (API-023 unticked) | Lenient decode (bad hex or a missing role/mode → derive or ignore).<br>`AttndTheme(theme)` builds `expressiveLightColorScheme()` / `darkColorScheme()` with `primary` = primary, `secondaryContainer` / soft surfaces = secondary, `tertiary` = tertiary (progress, badges). Send each colour's matching `on*` as given; the server guarantees contrast.<br>The Live Update's `ProgressStyle` segment colour comes from the light primary. |
| **Status pills and banners swipe to dismiss**: up 1:1, down rubber-bands ≤ 24 dp, released past 30 % or flung up → slides off; Reduce Motion: shorter slide, no spring; dismissed for the session; a new refresh problem shows again; Options → Status keeps the info. "Offline · Updated 3:41 PM · everything still works" / "Can't update · …". No **Demo** pill (iOS removed it, Nov 21). | `SwipeDismissRule`, `eventStatusBanners()` in I`ContentView.swift`, `EventStatusBadgeKind`, `AppState.dismissedStatusBadges` | `EventStatusPills` (Demo + Ended), `OfflineNotice` row, `UpdateBanner` (dismissible by button) | No swipe; still shows Demo | Pure `SwipeDismissRule` in domain (with its tests) plus `Modifier.swipeUpToDismiss` (`anchoredDraggable` / `draggable` + `Animatable`).<br>Honour `Settings.Global.ANIMATOR_DURATION_SCALE == 0` as Reduce Motion.<br>Remove the Demo pill. Add an Options "Status" row. |
| **In-app banner for organizer updates that arrive while the app is open** (~6 s, urgent 10 s; tap → Updates at it; system banner suppressed, Notification Center keeps it) | `InAppBannerOverlay` | Always a system notification (A`push/PushNotifications.kt`); `AttndMessagingService` knows `isForeground` | Missing | While started (`AndroidLogSink.isForeground`), post the notification silently (`setSilent(true)`, low-importance channel or no heads-up) so the shade keeps it. Emit the update to a `SharedFlow` that the shell shows as a top banner. Log `ui.banner`. |
| "Update available" banner (API-003) | `AppState.dismissedUpdateVersion` | A`ui/screens/UpdateScreen.kt`, `DevicePrefs.dismissedUpdateVersion` | Parity | Keep. Make it swipe-dismissable like the other banners. |
| Urgent card on Home: compact, swipes away sideways with Undo; stays in Updates | `dismissUrgentWithUndo` | Urgent banner (`DevicePrefs.dismissedUrgent`, one id) | iOS keeps a set of dismissed ids | Store a set, add the swipe and Undo. |

### 1.5 Options, device and network

| Feature | iOS | Android today | Gap | Android approach |
|---|---|---|---|---|
| **Options → Permissions**: Location and Notifications with the system's status. Allowed → Attnd's switch; never asked → the switch asks; denied → Open Settings. Refreshed on resume. On every new join, what the system already allows is turned on. | I`SettingsView.swift`, `AppState.applySystemPermissionsOnJoin` | Toggles "Push notifications" and "Show my location" (A`ui/screens/OptionsScreen.kt`); `PermissionLog` | No status or denied handling | `PermissionsSection` with `ContextCompat.checkSelfPermission` + `shouldShowRequestPermissionRationale`. Remember "asked once" to tell never-asked from denied. Open Settings with `ACTION_APPLICATION_DETAILS_SETTINGS` / `ACTION_APP_NOTIFICATION_SETTINGS`. Refresh in a `LifecycleResumeEffect`. |
| **Follow on Lock Screen default on**; Options "Start now" | `AppState.followsOnLockScreen` | Default on only once there are hearts or a plan (`PlanStore.follows`) | Differs | Default on. |
| **Test server switch** (TestFlight / Xcode installs): Production / Dev; applies at the next launch; switching leaves the event | `ServerSwitch` in I`APIClient.swift`, `AppState.switchServer`, `lifecycle.server_switch` | `BuildConfig.ATTND_SERVER` fixed per build | Missing | In `BuildConfig.DEV` builds (`debug`, `dev`) only. Pref read at process start; leave sent to the old server; drop its JWT.<br>A `debug` build's static key only works on dev, so the Production choice needs Play Integrity (dev build type) — see Risks. |
| **Device id survives reinstall** (owner: Block Store) | `DeviceIdentity.resolve` in I`Helpers.swift` (UserDefaults → Keychain this-device-only → new UUID), BusinessLogicTests cases | `DevicePrefs.deviceId` (SharedPreferences, in Auto Backup) | Lost on reinstall unless a backup restore happens | `com.google.android.gms:play-services-auth-blockstore` (first-party).<br>Pure `DeviceIdentity.resolve(prefs, blockStore, make)` in domain, with the iOS test cases.<br>At startup: prefs, else Block Store's `retrieveBytes`, else a new UUID; fill whichever store lacks it.<br>`StoreBytesData.setShouldBackupToCloud(false)` to match iOS's "this device only"; check the setter name on the current release.<br>Resolve before the first API call (`AttndApplication`, async with a short timeout, then prefs). |
| **Network tidy-ups**: `app-status/` once per launch, then at most hourly on foreground; `POST devices/` only when the token, opt-in, event or Live Activity token changed since the last accepted registration (or it failed); foreground / poll refreshes skip within 15 s of a successful fetch (pushes and pull-to-refresh always go); 15 s poll after a failed refresh | I`CLAUDE.md` → Logging "Network tidy-ups", `LiveRefreshPolicy` in I`Helpers.swift` | `appStatus` at every start (A`data/EventRepository.kt` l.126); `registerDevice` at launch + `needsDeviceRegistration`; `LiveUpdates.pollIntervalMs` 60 s / 5 min | Partly | Extend A`domain/LiveUpdates.kt` (pure + tests): `shouldCheckAppStatus(last)`, `shouldRegister(lastAccepted, current)`, `shouldRefresh(source, lastSuccess)`, and a 15 s retry interval. |
| **Offline queue**: `POST leave/` (pending leaves, retried at launch and after a refresh), join retried after the next successful refresh, devices pending | `pref.pendingLeaves`, `AppState.retryPendingServerWork` | `leave()` is fire-and-forget (A`data/EventRepository.kt` l.209) | Leaves are lost offline | `DevicePrefs.pendingLeaves` (set of event ids) and `retryPendingServerWork()` after each successful refresh and at launch. |
| **API-025 free limit** (`409 event_full_free` → show the server's message, back to launch, no retry) | Not built on iOS either | Generic join error (`describeJoinError`) | Neither app | Optional (phase 12). It's tiny; recommended even though iOS lacks it. |
| **API-026 preview phones**: `?preview=<token>` kept from join links / QR, sent once as `preview_token` with `join/`; `preview: true` → `GET changes/?since=` long-poll while resumed (`changed` → refetch, `retry_after`, errors 15 s); `jitter_ms` from `content_updated` (0…jitter, 0 = at once, missing = 0–10 s) | `PreviewPoll`, `DeepLink.previewToken`, `ContentUpdatePolicy.jitter` | Not built; `LiveUpdates.pushJitter` fixed 0–10 s | Missing (API-026 unticked) | A`domain/JoinLinks.kt` keeps the `preview` query. `JoinRequest.previewToken` (never logged; add it to `Redact`). `DevicePrefs.previewEventId`.<br>`PreviewPoller` on `repository` scope, started / stopped by `MainActivity` `onStart` / `onStop` (a `HttpURLConnection` read timeout > 25 s).<br>`jitter_ms` from FCM data. |
| App Links for dev.attnd.app | `applinks:dev.attnd.app` | attnd.app only (manifest) | Small | Add `dev.attnd.app` to the `/join/` + `/p/` intent filter with `autoVerify`, if attnd-web serves `assetlinks.json` there (check first). Links the app builds use the server's host on dev, like `ServerSwitch.linkHost`. |

### 1.6 Live Update (iOS Live Activity)

| Feature | iOS | Android today | Gap | Android approach |
|---|---|---|---|---|
| **Live Activity → Live Update parity** | `LiveActivityPlanner` in I`LiveFollowLogic.swift`, IT`LiveFollowTests.swift` (26), I`LiveFollowController.swift`, `ContentState` in I`LiveActivityModel.swift` | A`domain/LiveFollow.kt` (`LiveSource` plan / hearts / event, `walkMinutesToNext` from the current item **or the user**), A`live/*`, A`AT/LiveFollowTest.kt` (9) | Rules have diverged | Port `LiveActivityPlanner` (see the next three rows) and re-sync the tests to the iOS 26 cases (the same fixture). |
| What it follows | `followDay` with My schedule's timed places (`FollowPlace`), plan > My schedule > the day's items | Plan, else hearts, else the event's items | No timed places; no My schedule | Follow iOS: `followDay`, honouring `bundle.live_activity.follows` (`my_schedule` / `everything` / `location`), `buttons` (`skip`, `map`; `add` accepted, not drawn) and `stale_text`. |
| Now / Next always filled | Now = My schedule's running entry, else what's on at the event ("At the event", `now_source`); Next / after likewise; "Free until 3:00 · Next: X at Y"; "Gates open 10:00 · First: X" | Current / next / after from the followed items only | Fallbacks missing | Follow iOS. |
| User position | **No direction, distance or walk from the user's position** (batch 7); walk only from Now's place | Uses the user's last location | Differs | Drop the user-position input to the Live Update's content. Reminders keep "walk from the last position" (iOS does). |
| **Starts on join** while the app is in the foreground, even days ahead; header hint "Tap ♥ on anything and it shows here" until something is in My schedule; countdowns > 1 h as "2 hr, 5 min" / "2 days" | `decision`, `LiveActivityPlanner.emptyHint` | Window: 2 h before the first item → 30 min after the last | Differs | Post the Live Update on join when foreground (needs `POST_NOTIFICATIONS`). Before the window: a relative countdown text in place of the chronometer (`setWhen` countdown only within the last hour). See Risks: promotion criteria. |
| Buttons | Skip (next) + Map; Dismiss for urgent; **no Heart / Add button** (removed Nov 21) | Heart / Unheart, Skip next, Show on map | Extra action | Remove Heart. The API-009 counting path from the Live Update goes too. |
| Theme | `LiveThemeMapping` | Brand purple | Missing | Light primary for segments. Urgent stays red-ish (`BigTextStyle`). |
| Running late / leave now | `LiveAssist.runningLate` via position samples only while a screen tracks the location | Live Update title "Leave now for X" from the last position | Differs | Keep `LiveAssist` in the app's live hints. The Live Update gets `leave_by` only from foreground samples ≤ 30 min old, like iOS's content. |

### 1.7 Logging, performance, onboarding, search, map

| Feature | iOS | Android today | Gap | Android approach |
|---|---|---|---|---|
| **Main thread / performance rules**: decode and IO off main; derived state computed off the main thread and assigned in one go; search index built off main; `MainThreadWatchdog` logs `perf.hang` (> 100 ms) in Debug/Dev builds; signposts `bundle_apply`, `bundle_save`, `search_index`; personal state written ~0.6 s after the last change; no per-frame animations | I`CLAUDE.md` → "Main thread", "Hitch audit", "Profiling fixes"; I`AppLog.swift` | Repository IO on `Dispatchers.IO`. `EventViewModel.uiState` is a big `combine` on the VM scope, i.e. **Main** (A`ui/EventViewModel.kt` l.281). Prefs writes per tap (`prefs.edit`, apply = async disk, fine). No hang detector. | Partly | `uiState` derivation `.flowOn(Dispatchers.Default)` (the work is pure).<br>The search index and planner run on `Default` (`perf_planner_ms` stays).<br>Hang detector: a `Handler(Looper.getMainLooper())` ping thread at 50 ms → `perf.hang` with `ms`, in `BuildConfig.DEV` only. Optionally `androidx.metrics:metrics-performance` (JankStats, AndroidX) for `perf.frame_drops`.<br>Trace sections `bundle_apply` / `bundle_save` / `search_index`.<br>Baseline Profile (`androidx.profileinstaller`; the generator is a separate module) — optional, AndroidX. |
| **Logging events**: all in attnd-web `LOG_EVENTS.md`, missing from Android's list | I`AppLog.swift` call sites (iOS emits `perf.hang`, `lifecycle.server_switch`, `live.reminder.scheduled`, …) | A`domain/LogEvents.kt` lacks: `perf.hang`, `perf.frame_drops`, `lifecycle.server_switch`, `live.reminder.scheduled`, `net.app_status`, `search.open`, `ui.banner`, `map.fit`, `map.pin.selected`, `map.route.shown`, `map.tiles.error`, `location.fix`, `storage.prefs.migrated`, `storage.images.pruned`, `planner.changed`, `logging.ring.trimmed` | Missing names (all already in `LOG_EVENTS.md`) | Add the constants and emit them with the features above. New schedule / assistant events need a `LOG_EVENTS.md` row first (attnd-web). |
| **Debug builds never upload** (local Xcode); Dev (TestFlight) builds always | `LogPolicy.isDevBuild` = `DEV` condition | `debug` and `dev` both `DEV = true` → both upload | Differs | Follow iOS: a new `BuildConfig.UPLOAD_LOGS` (true for `dev`, false for `debug`). Local debug builds keep logcat, the ring buffer and the viewer. |
| Onboarding: catalogue-2 `onboarding` screen (`intro`, `welcome` title/text/https image, `location`, `notifications`, `lock_screen`, `plan` only with the model) | `OnboardingLayout` in I`ScreenLayout.swift`, I`OnboardingView.swift` | A`ui/screens/OnboardingScreen.kt` fixed cards | Missing | Draw from the layout; add the `lock_screen` and `welcome` cards. |
| Search sheet (without the model); a question with no match searches its keywords | I`EventSearchView.swift` | Search via the bar overlay (`AssistantBarOverlay`) | UI differs | See the assistant row in §1.3. Rules stay in A`domain/EventSearch.kt` (shared tests). |
| Places rows "3 events ⌄" disclosure (`PlaceEvents`); place detail walk map (`PlaceWalkMap`, > 20 m, ≤ 8 km) | I`DisplayLogic.swift`, I`LocationsView.swift` | Location rows have Now/Next only; walk route on the Map card only | Missing | Port `PlaceEvents` and `PlaceWalkMap` with the `DisplayLogicTests` cases. Lite-mode `GoogleMap` (like `MiniMap`). |
| Map polish (batch 7): cluster only overlapping pins (< 24 dp apart); location button cycles off → follow → follow with heading; route pulse; heading cone on the user dot; category chip refits the camera (`MapRegionMath.filterRegion`) | I`MapView.swift`, I`DisplayLogic.swift` (`MapClustering.overlapping`) | maps-compose-utils `Clustering` (default distance algorithm); recenter button | Partly | A `ClusterManager` algorithm with a 24 dp radius (`NonHierarchicalViewBasedAlgorithm` or a custom `ScreenBasedAlgorithm`).<br>The follow modes use `CameraPositionState` + `HeadingSource`.<br>Refit on chip change. The pulse stays optional (Reduce Motion off). |

### 1.8 Removed on iOS, so they go on Android too (§4)

These need code removed on Android:
- the home-screen widget (A`widget/`);
- AppFunctions and dynamic shortcuts (A`assistant/`);
- Add to calendar (A`domain/CalendarEntries.kt`, `AssistantIntents`);
- the venue geofence (A`live/VenueGeofence.kt`);
- "Ask about this" (`EventActions.onAskAbout`);
- the Demo pill;
- the Live Update Heart action;
- Explore + My plan tabs;
- `AssistantTranscript` (replaced by `ChatTranscript`).

Weather was never built.

### 1.9 Tracker (attnd-web `API_CHANGES.md`) entries without an Android ✅

| Entry | Android column today | Plan |
|---|---|---|
| API-001, 003, 004, 005 | ✅ dev · ⬜ prod | Ticked at the next production release, not a code task |
| API-013 | ⬜ (but built: commits `56c461f`, `1b7d413`) | **Tick now** (docs-only, attnd-web) in phase 1's tracker commit |
| API-014 | ⬜ (built: `PerfRecorder`, `PerfMetrics.kt`) | Tick with API-013 |
| API-017 | ⬜ later (parked) | Nothing (parked) |
| API-018 context | ⬜ later | Phases 1 (decode) + 5 (screens) + 6 (assistant) |
| API-019 | n/a (no App Clip) | Never send `app_clip` |
| API-021 plan_suggestions | ⬜ | Phase 12 (optional; iOS hasn't done it) |
| API-022 image_credit | ⬜ | Phases 1 + 5 |
| API-023 theme | ✅ | Phase 7 (`domain/Theme.kt`, `AttndTheme`) |
| API-024 layouts | ⬜ later | Phase 5 |
| API-025 free limit | ⬜ | Phase 12 (optional; iOS hasn't done it) |
| API-026 preview / jitter | ✅ | Phase 9 (`domain/PreviewPhone.kt`, `data/PreviewPoller.kt`; `jitter_ms` in phase 1) |
| API-027 catalogue 2 + `live_activity` | ⬜ parked | Phases 5c + 8 |
| API-030 Apple Music | ⬜ paused | Phases 1 + 5 |
| API-031 Google photo credit | ⬜ paused | Phases 1 + 5 |

---

## 2. Architecture mapping

### 2.1 Layers

| iOS | Android (target) |
|---|---|
| `AppState` (`@MainActor @Observable`, ~2,350 lines): event, bundle, display, tabs, device, prefs, joins, push, hearts / My schedule, urgent, follow, preview, usage | **Split the 1,260-line `EventViewModel`** (phase 2) into: `EventRepository` (already: bundle, sync, joins, devices, cache) + feature controllers in `AppGraph` (application scope, so services, receivers and the Live Update use them without a VM): `ScheduleController` (My schedule + Undo), `PreviewPoller`, `PermissionsState`; and UI-scoped holders the VM owns: `NavigationState` (tabs, pushed details, map focus), the existing `AssistantController`. `EventViewModel` becomes a thin `combine` of their `StateFlow`s into `AppUiState`, computed on `Dispatchers.Default`. One controller per file, so later phases touch different files. |
| `AssistantController` (turns, proposals, chat, executor) | A`ui/assistant/AssistantController.kt` stays: it gains the chat transcript, stage, tool loop and memory; it loses the dock / answer-card state |
| `PlanStore` (plan per event, undo stack in files) | A`data/PlanStore.kt` stays. The undo history and conversation move from `DevicePrefs` strings to files in `filesDir/assistant/` (iOS moved them off UserDefaults on Nov 22; large prefs strings are read on every prefs load) |
| `EventPersonalState` (`pref.event.<id>` JSON, written ~0.6 s after the last change) | One per-event JSON in `DevicePrefs` (`personal_<id>`: schedule entries, updates seen, dismissed banner / urgent ids, follow, skipped) through a debounced writer; old keys read once (migration) |
| actors `EventStore` / `ImageStore` | `EventRepository` file cache + `ImageDiskCache` (already off main) |
| SwiftUI views per screen; `ComponentView` the one renderer | Composables per screen; `ComponentView(component, surface)` the one renderer; screens receive state slices + callbacks (the existing rule) |
| `NavigationStack` paths (`searchPath`, `mapNavigation.path`, `pagePath`) | Per-tab back stacks as `SnapshotStateList<Entry>` held in `NavigationState` (saveable) — or Navigation 3 (`androidx.navigation3`, AndroidX) if phase 0 brings a stable release; decide in phase 4 by what phase 0 shipped (phase 4: kept in `NavState`, no Navigation 3). `BackHandler` / predictive back pops. |
| `fullScreenCover` (My plan chat), sheets (Options, search) | Full-screen destination for the chat; `ModalBottomSheet` for search and Options |
| `ReservedRegionAvoiding`, `ArrangementView` | `currentWindowAdaptiveInfoV2().windowPosture` hinge bounds + `FoldAwareSplit` (exists) (§3) |
| `MetricKitReporter` | `ExitReporter` (exists, `ApplicationExitInfo`) |
| `os.Logger` + `OSSignposter` | `AppLog` + `AndroidLogSink` + `android.os.Trace` (exist) |

### 2.2 Pure domain: port 1:1 with the same test cases

The rule stays: `domain/` is pure Kotlin with JVM tests and no Android imports. iOS ports Android's domain files 1:1
already; for the new work the direction flips.

**Already shared (same fixture, same cases):**

| Android test | iOS test | State |
|---|---|---|
| `EventSearchTest` | `EventSearchTests` | Shared |
| `RelativeTimeTest`, `GeoTest`, `WalkingTest`, `GlanceTest`, `HeartsTest` (+ `GlanceFixture`) | `GlanceTests` | Shared |
| `PlannerTest`, `PlanRequestTest` (+ `PlannerFixture`) | `PlannerTests`, `PlanRequestTests` | **Diverged** on reason wording ("In your schedule", "Picked by", "Similar to"): re-sync in phase 3a |
| `AssistantBlocksTest`, `PlanEditingTest`, `AssistantExtrasTest` (+ `AssistantFixture`) | same names | Shared; re-check after phase 6a (calendar removed: iOS lists `add_to_calendar` in `neverAvailable` and drops the `calendar` block) |
| `AssistantRouterTest` | `AssistantRouterTests` | Shared (re-synced in 6a) |
| `AssistantFlowTest` | `AssistantFlowTests` | Shared (re-synced in 6a; widget cases Android-only; `ChatTranscript`, `doneLine`, `looksForeign` in `ConversationContextTest`) |
| `PlanProposalTest` (+ `PlanTabTest` tabs) | `PlanProposalTests` | Shared (re-synced in 6a) |
| `LiveFollowTest` (9) | `LiveFollowTests` (26) | **Diverged**: re-sync in phase 8 |
| `LoggingTest`, `LogRedactionTest`, `DiagnosticsUploadTest` | `LoggingTests` | Shared spec; keep |
| `ScheduleGroupingTest`, `LocationGroupingTest`, `DisplayConfigTest` | `DisplayLogicTests` (35) | Partly; port the missing cases (`PlaceEvents`, `PlaceWalkMap`, map clustering overlap, `filterRegion`) in phase 5b |
| `SupportInfoTest`, `JoinLinksTest`, `LiveUpdatesTest` | `BusinessLogicTests` | Partly; port `DeviceIdentity`, `LiveRefreshPolicy` / `ContentUpdatePolicy` (jitter), preview token cases in phases 1 / 9 |
| `ApiMapperTest` | `ModelDecodingTests` (44) | Partly; port the lenient-decoding cases for theme, context, image credit, Apple Music, `live_activity`, `layout` in phase 1 |

**Android-only tests:** `QrCodeTest`, `ImageCachePlanTest`, `ScreenshotRequestTest`, `UsageReportingTest`,
`AttndApiClientTest`, `EventTimeTest`. Keep them.

**iOS-only, to port (new Kotlin file → test):**

| iOS source (I) | Android file (A`domain/`) | Tests (IT → AT) | Phase |
|---|---|---|---|
| `MySchedule.swift` | `MySchedule.kt` | `MyScheduleTests` → `MyScheduleTest` | 3a |
| `TabLayout.swift` | `TabLayout.kt` (+ `MapNavigation`) | `TabLayoutTests` → `TabLayoutTest` | 3a (pure) / 4 (UI) |
| `ScreenLayout.swift` (+ `DetailColumns`, `HomeColumns`, `HomeColumnWidths`, `TabletopSplit`, `AboutText`, `TabListLayout`, `UpdatesLayout`, `OnboardingLayout`, `LiveActivitySettings`, `FoldRules`) | `ScreenLayout.kt` (+ `FoldRules.kt`) | `ScreenLayoutTests` → `ScreenLayoutTest` (37; also mirrors attnd-web `events/tests_layout.py`) | 5a |
| `Component.swift` | `Component.kt` | `ComponentTests` → `ComponentTest` | 5a |
| `ConversationContext.swift` | `ConversationContext.kt` | `ConversationContextTests` → `ConversationContextTest` | 6a |
| `PlanDock.swift` | into `PlanTab.kt` → rename to `PlanProposal.kt` | `PlanDockTests` → `PlanDockTest` | 6a |
| `DuoFeatures.swift` (`TimeTravel`, `LinkedPanes`, `StageCompare`, `TabletopGlance`, `FlyerPlaces`, `DragPayload`, `UndoToastPane`) | `FoldFeatures.kt` | `DuoFeaturesTests` → `FoldFeaturesTest` | 10 |
| `Models.swift` context / theme decoding | `data/api/ApiDtos.kt` + `ApiMapper.kt` | `ContextDecodingTests` → `ContextDecodingTest` | 1 |
| `Helpers.swift` `DeviceIdentity`, `LiveRefreshPolicy`, `ContentUpdatePolicy`, `LastUpdatedText`, `MediaCache.isLivePlacePhoto` | `DeviceIdentity.kt`, `LiveUpdates.kt`, `ImageCachePlan.kt` | from `BusinessLogicTests` | 1 |
| `ThemeColors.swift` `ThemePalette` (pure part) + `LiveThemeMapping` | `Theme.kt` (domain) | from `ComponentTests` (API-023 decoding) / `LiveFollowTests` | 7 |
| `AssistantModel.swift` `TokenBudget`, `assemble`, `answerText`, `namedEntities` | `AssistantModel.kt` | the matching `AssistantFlowTests` / `ConversationContextTests` cases | 6a |
| `LiveFollowLogic.swift` `LiveActivityPlanner`, `ReminderPlanner`, `FollowPromptText` (skip: iOS-only "tap to follow") | `LiveFollow.kt` | `LiveFollowTests` | 8 |
| `SwipeDismissRule` | `Banners.kt` | the iOS cases | 4 |

**Not ported (iOS-only):**
- `AssistantJSON.swift`: kotlinx.serialization covers it.
- `AssistantSearch.swift`: it is itself a port of Android's search.
- `AppHandover.swift`: App Clip only.
- iOS Live Activity widget code.

**Every ported test keeps:**
- the same fixture data;
- the same `now`;
- `America/Chicago`;
- `en_US`.

---

## 3. Foldables (the iOS Duo work, Android equivalents)

Tablets stay excluded from distribution (Play Console device catalog). The layouts below must work on unfolded
foldables and therefore on any large window. Use size classes and container sizes, never device checks.

**Large-screen quality guidelines:** checked online Nov 22, 2026. They are now the **Adaptive app quality guidelines**
(developer.android.com/docs/quality-guidelines/large-screen-app-quality redirects there):
- **Tier 3** "Adaptive ready": full screen / full window, no letterboxing, critical flows work, basic keyboard / mouse /
  stylus.
- **Tier 2** "Adaptive optimized": layouts for every size, enhanced input.
- **Tier 1** "Adaptive differentiated": multitasking, foldable postures, drag and drop, stylus.
- Google Play will require **at least Tier 3 for foldables from April 2027**.
- The Play large-screen badge needs Tier 2 (all sizes) or Tier 1.
- Archive: Tier 2 of 2026-04-07 and the 2026-01-21 large-screen guidelines.
- Test targets include foldables with 1:1 (802×802 dp) and 4:3 (1024×768 dp) inner displays. Emulators: Phone >
  Foldable (1:1), Phone > Foldable (4:3), Pixel Fold.
- Play listings should carry folded and unfolded screenshots. That is the ROADMAP item "foldable screenshots for Play".

I couldn't fetch the per-tier checklist pages' item IDs in this session. Phase 10 starts by reading
`…/adaptive-app-quality/tier-3`, `tier-2` and `tier-1` and turning each item into a checklist row in
`docs/FOLDABLE_CHECKLIST.md`, the Android counterpart of iOS's `IPHONE_DUO_CHECKLIST.md`. **Target: Tier 2 fully,
Tier 1 where the iOS Duo features map.**

### 3.1 Window info and postures

Window info:
- Already `currentWindowAdaptiveInfoV2()`: `windowSizeClass` with the `WIDTH_DP_MEDIUM_LOWER_BOUND` /
  `EXPANDED` / L / XL breakpoints; `windowPosture.isTabletop`, `allVerticalHingeBounds`, `allHorizontalHingeBounds`,
  `hingeList` (separating, occluding).
- Keep it the only source.
- Add a pure `FoldPosture.of(hinges, windowSize)` in domain (flat / book / tabletop, fold position), equivalent to
  iOS `FoldPosture.of` from `.division` regions, with tests.
- Occluding hinges (no content under them) are covered by `FoldAwareSplit` (A`ui/components/FoldAwareSplit.kt`).
  Move its pure `splitAt` into domain with tests.

Breakpoints, the same rules as iOS:
- detail two columns ≥ 700 dp **and** ≥ 3 sections besides the header side (`DetailColumns`);
- Home two columns from 700 dp of its own width (`HomeColumns` + `HomeColumnWidths`: the vertical hinge + 20 dp
  gutter each side, else two halves 40 dp apart);
- readable single column ≤ 680 dp.

Measure with `BoxWithConstraints` / `onSizeChanged` on the container, not the window.

### 3.2 Scaffolds

**Navigation:** `NavigationSuiteScaffold` stays.
- Short bar folded, collapsed `WideNavigationRail` unfolded.
- The floating My plan / search button goes in the rail header when the rail shows (exists today for search), else
  the bottom-end FAB.
- On the Map it joins the map's control group (§3.3).

**Schedule / Places:**
- `NavigableListDetailPaneScaffold` (exists, A`ui/components/ListDetail.kt`).
- Unfolded with nothing selected, the Schedule detail pane shows **stage vs stage** (iOS feature 6) instead of an
  empty pane.

**Map + pane:** iOS's batch-7 rule:
- unfolded, the Now & Next / schedule pane **leads** and the map **trails**;
- no divider;
- the pane is a plain list with a compact header row.

Android today has the map first (fraction 0.6). Flip it to match.

**My plan:** unfolded and wider than tall, the chat column plus a **planning board** pane ("Your Day" timeline with
its route + "Coming Up" sets to drag or add with +, plus a "Last Answer" segment; iOS feature 4). Use
`SupportingPaneScaffold` (material3-adaptive) or `FoldAwareSplit` on the hinge. Taller than wide → the chat alone.

**Flyer:** unfolded and wider than tall, the flyer leads and a map trails.
- Chips for the places the flyer names: `FlyerPlaces` with on-device text recognition of the cached flyer image.
- Needs ML Kit `text-recognition` (first-party Google, allowed by the dependency policy).
- Every place when there's no text or the flyer is a PDF.

### 3.3 Keeping state across fold / unfold

**Problem:** the activity has no `configChanges`, so a fold recreates it. Compose then rebuilds, and `MapTab` calls
`map()` in different `when` branches (A`ui/MapTab.kt` l.77–105), so the `GoogleMap` / `MapView` is a new instance on
every fold (tiles reload, the camera is restored from `rememberCameraPositionState`'s saver).

**Fix:**
1. Declare `android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation|density|keyboardHidden"` on
   `MainActivity`. Compose handles these itself. The app has no size or orientation resource qualifiers; check with
   `find res -type d`, where only `values-v36` exists. `uiMode` stays out, so a dark-mode switch still recreates and
   picks up resources.
2. Draw the map in **one call site**: a custom `Layout` (or `movableContentOf { EventMap(...) }`) that places the
   optional pane before or beside the map by posture. This is iOS's `MapTab` with `AnyLayout`, keeping one `MKMapView`.
   Move the map's selection, chips and time travel into a saveable `MapUiState` so the pane's pick is the selected pin
   when folded.
3. Keep `NavigationState` (pushed details, selected tab), composer and search text, open sheets and the chat in the
   VM / controllers, never in composition-only state.

**Primary pane rules** (`FoldRules`, ported and tested):

| Screen | Primary pane | When folded |
|---|---|---|
| Map | the map | — |
| Schedule / Places | the list | a selected detail is pushed over it (`ListDetailPaneScaffold` does this when the navigator keeps the selection) |
| Home | one ordering of sections | — |
| My plan | the chat | — |
| Details | the detail | — |

**Scroll positions:**
- Home keeps its top section across fold / unfold / tabletop: `LazyListState` saved by key (`rememberSaveable`) and
  restored to the first visible section's key, not the index, because two-column ↔ one-column changes indices.
- The chat stays anchored to the bottom unless the person scrolled up (`followsBottom`).

**Undo toast pane** (`UndoToastPane`):
- where the action was tapped (record the tap's window x), else the screen's primary pane;
- centred there, never across the hinge;
- above the floating button row.

On Android: a `SnackbarHost` placed in a `Box` aligned to that pane's bounds.

### 3.4 Tabletop mode

`windowPosture.isTabletop`:
- **Map:** map top, pane bottom, split on the horizontal hinge (exists).
- **Home** (`TabletopSplit` + `TabletopGlance`): top half = a glance that never scrolls.
  - The glance shows the event, the time, urgent in solid red, Now with progress, and Next with a live countdown, in
    big type scaled to the half.
  - Bottom half = the rest of Home as a list, **or At the Event mode**: big Map / heart / Directions / What's next
    controls (`TabletopControls`).
  - The mode is offered once (`TabletopOfferCard`; `DevicePrefs.tabletopOffered`) the first time the phone is
    half-folded while the event is on. The choice is kept in `tabletopAtEvent`.

### 3.5 Drag and drop between panes (Tier 1)

Compose foundation `Modifier.dragAndDropSource` / `dragAndDropTarget`, with `ClipData.newPlainText` payloads from
`DragPayload` (`"attnd:item:12"` / `"attnd:place:5"`, parsed and tested in domain).

Sources:
- schedule rows;
- item / place cards;
- the board's sets;
- the places pane's rows.

Targets:

| Drop on | Result |
|---|---|
| the map | flies there |
| Home's Up Next | added to My schedule, with Undo |
| the planning board | a plan proposal (chat card, Apply / Undo) |

`DRAG_FLAG_GLOBAL` is not needed (in-app only).

### 3.6 The other iOS Duo features

| Feature | iOS | Android |
|---|---|---|
| Time-travel map | `TimeTravel`, `TimeTravelScrubber`, `TimeTravelDock` | A docked `Slider` row at the map's bottom edge (expanded width), clear of the Maps logo / legal text. It lights the pins on at that time, dims the rest, snaps to set starts within 10 min with `HapticFeedbackType.SegmentTick`, and resets to "Now". Collapsed to a "Time" chip while a place card shows. |
| Linked panes | `LinkedPanes.nextItem` | Tapping a set in the map's pane flies to its stage, selects the pin and highlights the row. Tapping a pin scrolls the pane to that place's next set. |
| Category chip refits the camera | `MapRegionMath.filterRegion` | One pin → street zoom; several → `LatLngBounds` with padding. |

### 3.7 Foldable store screenshots

Extend `store-screenshots.yml` / `screenshots.sh`:
- a Pixel Fold or "Foldable (4:3)" emulator, unfolded and folded;
- screenshot mode keeps the built-in layouts.

---

## 4. Left out on Android (owner's rule: follow iOS; leave out what Android can't match)

### 4.1 Left out: Android can't match iOS

- **App Clip and its handover**: Android has no equivalent; Google Play Instant was shut down in December 2025.
  The join flow stays QR → `/join/<code>/` → App Link (installed) or the Play Store with
  `referrer=event_code%3D<code>` → Play Install Referrer joins on first launch (A`data/InstallReferrer.kt`).
- **API-019 `app_clip`**: no clip, so the key is never sent.
- **The `attnd://` scheme**: it exists on iOS only because Universal Links don't open from the same domain. The
  console already sends Android to `intent://…;scheme=https;package=…`, which Android handles.
- **iOS-only plumbing**: ActivityKit push tokens (`live_activity_token`), the "tap to follow" local notification
  (iOS has no background start; Android posts the Live Update itself), App Intents buttons, the Dynamic Island,
  Watch Smart Stack, CarPlay.
- **Liquid Glass**: an iOS look; Android keeps Material 3 Expressive.

### 4.2 Removed on Android, following iOS's removals

- **Assistant, planner and "Plan your day" without the on-device model**: hidden, as iOS hides them without Apple
  Intelligence. Android currently keeps a router-only planner.
- **Explore and My plan tabs**: replaced by iOS's tab model (event tabs + Home first) and the floating My plan button.
- **Home-screen widget** (A`widget/PlanWidget.kt`, Glance dependency): iOS removed its widget on Nov 19, 2026.
- **AppFunctions, dynamic shortcuts and Gemini integration** (A`assistant/*`, KSP + `appfunctions` dependencies,
  `res/xml/app_functions_metadata.xml`, `values-v36` bool): iOS removed Siri, Shortcuts, Spotlight and quick actions.
  `attnd-open://` stays: notification taps and Live Update links use it.
- **Add to calendar** (action, block, sheet): iOS removed it ("nobody will do that"); `add_to_calendar` is never
  available.
- **Venue geofence / "Tell me when I arrive"** (A`live/VenueGeofence.kt`, `RECEIVE_BOOT_COMPLETED` stays for alarms):
  iOS removed it; it was a no-op on Android anyway.
- **"Ask about this"** on details: iOS removed it on Nov 21; the `ask_about` section still validates and draws nothing.
- **Demo pill** (and any Demo label on the launch screen): iOS removed it on Nov 21; the free limit is the server's.
- **Live Update Heart / Unheart action**: iOS removed the Heart / Add button on Nov 21.
- **Token streaming of the model's reply**: iOS shows a working line, then the whole answer.
- **Upload from local `debug` builds**: iOS Xcode builds never upload; only `dev` builds do.
- **Weather**: never built; the Weather API is disabled.

### 4.3 Decided: Gemini Nano download (owner, Nov 22, 2026)

Apple Intelligence downloads its model by itself; ML Kit only downloads Gemini Nano when the app calls `download()`.
Owner's rule: the **My plan button shows when `checkStatus()` is AVAILABLE, DOWNLOADABLE or DOWNLOADING** (never when
the phone can't run Gemini Nano, and never in screenshot mode). Tapping it with the model not installed shows a small
prompt ("Download Attnd's on-device assistant? · about N GB, over Wi-Fi recommended" · Download / Not now); Download
calls `download()` and shows its progress in the prompt, then opens My plan once AVAILABLE (re-checked on every
resume, so a download finished in the background just works). No silent background download. Everything else that
needs the model (planner, "Plan your day" onboarding card) stays hidden until AVAILABLE, as on iOS.

Note for the owner: Gemini Nano runs on a narrower set of phones than Apple Intelligence (recent Pixel / Galaxy
flagships), so most Android attendees will see the plain search button and no planner.

---

## 5. Phased implementation order

Rules for every phase:
- It ships on its own; `./gradlew testDebugUnitTest lintDebug assembleDebug` stays green.
- Screenshot mode keeps working (`ScreenshotRequestTest`).
- Each phase updates the Android `CLAUDE.md` sections it changes.
- Commit straight to `develop` with the tracker ID when there is one (`API-024: …`).
- After shipping an API entry, tick its Android column in attnd-web `API_CHANGES.md`.

Phase 0 (toolchain and dependencies, minSdk 36) runs elsewhere. When it lands, phases may also drop the
`Build.VERSION.SDK_INT < 36` branches they touch (Live Updates, AppFunctions).

### Summary

| # | Phase | Size | Depends on |
|---|---|---|---|
| 1 | Data layer catch-up: decoding, network tidy-ups, offline queue, device id | M | 0 |
| 2 | Removals (follow iOS) + `EventViewModel` split | M | 0 |
| 3a | My schedule: domain + storage + migration (+ `TabLayout` pure port) | M | 1 |
| 3b | My schedule UI | M | 2, 3a, 4 |
| 4 | Tabs, navigation, Options, search sheet, banners, Permissions | L | 2, 3a |
| 5a | Server-driven screens + component catalogue: domain | L | 1 |
| 5b | Component renderer, details, Home, event info, Options rows, context / Apple Music / photo credit | L | 4, 5a |
| 5c | Catalogue-2 tab screens, Updates groups, onboarding layout | M | 5b |
| 6a | Assistant domain: memory, router grammar, budget, tool protocol, sequences | L | 3a, 5a |
| 6b | My plan chat UI + model tool loop | L | 4, 5b, 6a |
| 7 | API-023 themes | S–M | 1 |
| 8 | Live Update + reminders parity | M | 3a, 5a (`LiveActivitySettings`), 7 for colour |
| 9 | API-026 preview phones + `jitter_ms` | S–M | 1 |
| 10a | Foldables: continuity + adaptive layouts | M | 4, 5b |
| 10b | Foldables: differentiated features + checklist + store screenshots | L | 6b, 10a |
| 11 | Performance, hang detector, logging names, server switch | M | 4 |
| 12 | Tracker ticks + optional API-021 / API-025 | S | — |

### Phase details

**Phase 1 — Data layer catch-up (M)**
- **Scope:**
  - lenient DTOs and domain fields: `event.context` / `locations[].context` / `schedule[].context` (API-018, incl.
    `performers[].apple_music_url` API-030); `event.theme` (API-023); `locations[].image_credit` /
    `image_attribution_url` (API-022/031); `bundle.live_activity`; `bundle.layout` kept raw (`JsonElement`) for 5a;
    `display.tabs` accepting `home`;
  - FCM `jitter_ms`;
  - live place photos excluded from `ImageCachePlan`;
  - network tidy-ups (app-status hourly, devices on change, the 15 s refresh skip, the 15 s retry);
  - `pendingLeaves` + `retryPendingServerWork`;
  - Block Store device id;
  - tick API-013 / API-014 in attnd-web (docs only).
  - Not yet: sending `X-Attnd-Sections` (wait for 5b: sending it makes the server return Home in `display.tabs`).
- **Files:** A`data/api/ApiDtos.kt`, `ApiMapper.kt`, `AttndApi.kt`, A`domain/Models.kt`, `LiveUpdates.kt`,
  `ImageCachePlan.kt`, new `DeviceIdentity.kt`, A`data/EventRepository.kt`, `prefs/DevicePrefs.kt`,
  `ImageDiskCache.kt`, `push/AttndMessagingService.kt`, `AttndApplication.kt` (device id resolve only), `build.gradle`
  (blockstore).
- **Tests:** `ContextDecodingTest` (iOS 7), `ApiMapperTest` additions (from `ModelDecodingTests`), `LiveUpdatesTest`
  (jitter, tidy-up policies), `DeviceIdentityTest`, `ImageCachePlanTest` (live photos).

**Phase 2 — Removals + ViewModel split (M)**
- **Scope:**
  - delete the widget, AppFunctions + shortcuts (+ KSP plugin and dependency, manifest service, xml), calendar
    (domain, UI, action → `neverAvailable` like iOS), the venue geofence, "Ask about this", the Demo pill and the Live
    Update Heart action;
  - split `EventViewModel` into `NavigationState`, `ScheduleController` (a stub around `HeartStore` for now),
    `PermissionsState`, keeping behaviour identical otherwise.
- **Files:** A`widget/*`, A`assistant/*`, `AttndApplication.kt` (AppGraph), `MainActivity.kt`, A`ui/EventViewModel.kt`
  (+ new controllers), A`domain/CalendarEntries.kt`, `AssistantActions.kt` (calendar), `ui/assistant/AssistantIntents.kt`,
  `live/LiveNotifications.kt` / `LiveUpdateReceiver.kt` / `VenueGeofence.kt`, manifest, `build.gradle`, `res/xml`.
- **Tests:** remove the widget / calendar cases from `AssistantFlowTest` / `AssistantExtrasTest` the way iOS did;
  everything else green.

**Phase 3a — My schedule: domain + storage + migration (M)**
- **Scope:**
  - `MySchedule.kt`;
  - the per-event personal JSON with its debounced writer, plus migration;
  - `ScheduleController` real implementation (add with Undo, API-009 metric on membership changes only);
  - planner must-includes from My schedule and its wording;
  - `MySchedule.apply(plan)` on every `PlanStore.applyPlan` / undo;
  - `reconcile`;
  - also the pure `TabLayout.kt` port (no UI yet).
- **Files:** A`domain/MySchedule.kt`, `Hearts.kt` (mirror), `Planner.kt` (reasons), `TabLayout.kt` (new),
  A`data/ScheduleStore.kt` (new), `HeartStore.kt`, `PlanStore.kt`, `prefs/DevicePrefs.kt`, `ScheduleController.kt`.
- **Tests:** `MyScheduleTest` (8), `PlannerTest` re-synced (reason wording), `HeartsTest`, `TabLayoutTest` (11),
  `UsageReportingTest` (metric only on membership changes).

**Phase 4 — Tabs, navigation, Options, search, banners, Permissions (L)** — ✅ done (Nov 22, 2026)
- **Shipped:** `TabLayout` in the shell (`DisplayConfig.layout` / `legacyLayout`, `NavigationController` on `EventTab`;
  Explore, `exploreView` and the My plan tab deleted); Home placeholder (`HomeScreen.kt`, today's essentials); the
  search sheet (`SearchSheet.kt`) or the My plan button (§4.3 states) as the floating button; My plan as a full-screen
  destination; the gear on `optionsTab`'s root; `domain/Banners.kt` (`SwipeDismissRule`, `StatusBadges`, `InAppBanner`)
  + `SwipeUpToDismiss`, Options → Status; the in-app organizer-update banner; Options → Permissions
  (`domain/PermissionRules.kt`) and the join rule; Follow default on. Tests `BannersTest`, `PlanTabTest` tab cases,
  `NavigationControllerTest`. Navigation 3 was not adopted (state stays in `NavState`).
- **Deferred:** Home's urgent card with sideways swipe + Undo and a set of dismissed urgent ids (5b with Home's
  sections); hiding the planner / onboarding plan card without the model (6b: until then Home's My plan card keeps the
  planner on every phone, as the lead asked); distance + arrow on the sheet's place results.
- **Scope:**
  - iOS `TabLayout` in the shell; Home tab placeholder (built-in sections only until 5b; Home is always on);
  - Explore and the My plan tab gone;
  - the floating My plan FAB (with the model; it opens the existing plan UI until 6b) or the search FAB + search sheet;
  - Options gear on `optionsTab` only;
  - status pills with swipe-dismiss, Options → Status;
  - the in-app update banner;
  - Permissions section;
  - Follow default on;
  - the assistant gated on AVAILABLE + quiet download (per §4.3's answer);
  - screenshot mode unchanged (`legacy`).
- **Files:** A`ui/AttndApp.kt`, `ui/MapTab.kt` (rail / FAB placement only), A`ui/screens/OptionsScreen.kt`,
  `SearchScreen.kt`, `ui/components/Common.kt` / `Glance.kt` (banners), A`domain/PlanTab.kt` (`EventTabModel` removal),
  new `domain/Banners.kt`, `NavigationState.kt`, `push/PushNotifications.kt`.
- **Tests:** `PlanTabTest` tab cases → `TabLayoutTest`; `SwipeDismissRule` cases; `ScreenshotRequestTest`.

**Phase 3b — My schedule UI (M)**
- **Scope:**
  - heart semantics and menu;
  - the "When?" time dialog for places (suggested times);
  - Remind me;
  - Undo snackbar;
  - Schedule tab sections + organizer changes;
  - Places / Map "My Schedule" chip, Liked chip removed from Schedule;
  - reminders per entry scheduled through `LiveUpdateController` (the shared reminder part of phase 8; move the
    `ReminderPlanner` port here if 8 runs later).
- **Files:** A`ui/components/Glance.kt` (`HeartButton`, `PeekSheet`), A`ui/screens/ScheduleScreen.kt`,
  `LocationsScreen.kt`, `MapScreen.kt`, `ScheduleController.kt`.
- **Tests:** domain covered in 3a; add `suggestedTimes` cases if not yet ported.

**Phase 5a — Server-driven screens + component catalogue, domain (L)**
- **Scope:** port `ScreenLayout`, `Component`, `ScreenFacts`, `TabListLayout`, `UpdatesLayout`, `OnboardingLayout`,
  `LiveActivitySettings`, `DetailColumns`, `HomeColumns`, `HomeColumnWidths`, `TabletopSplit`, `AboutText`, `FoldRules`.
  Pure, no UI.
- **Files:** A`domain/ScreenLayout.kt`, `Component.kt`, `FoldRules.kt`, `AssistantBlocks.kt` (`Component` block).
- **Tests:** `ScreenLayoutTest` (37), `ComponentTest` (6).

**Phase 5b — Renderer, details, Home, context (L)** — ✅ done (Nov 22, 2026)
- **Shipped:** `X-Attnd-Sections: 2` + `EventBundle.layout` (saved ETag per catalogue); `ComponentView` (the one renderer,
  chat blocks + screen components; `ComponentBlock` in chat answers); Home from the `home` layout (two columns ≥ 700 dp);
  place / item details, event info, Options' event rows and the map overlay from their layouts (details in
  `LocationDetail.kt` / `ScheduleDetail.kt`, two columns ≥ 700 dp); API-018 sections, Listen on Apple Music (API-030),
  the photo credit with live attribution (API-031); Home's urgent card with sideways swipe + Undo; the Places "3 events"
  disclosure and the place walk map. Tests `ScreenPartsTest` (PlaceEvents, PlaceWalkMap, photo attribution, Home header
  and urgent, saved ETag, a server layout round trip), `AttndApiClientTest` (header).
- **Deferred:** Home's fold-aware column widths (10a); `PlaceWalkMap.region` (the lite map fits its own bounds); the
  "3 saved places" row opens nothing yet (3b's saved-places list); the remaining `DisplayLogic` cases (map clustering
  overlap, `filterRegion`) go with 10a / 11's map work.
- **Scope:**
  - `ComponentView` and one composable per section type;
  - item / place detail, event info, Options' event rows and the map overlay drawn from the layout;
  - the Home tab drawn from the layout;
  - context sections (API-018), Listen on Apple Music (API-030), photo credit (API-022/031);
  - `PlaceEvents` disclosure and the `PlaceWalkMap` card;
  - **turn on `X-Attnd-Sections: 2`** (needs 5c's screens to fall back to built-in; they do by rule).
- **Files:** new A`ui/components/ComponentView.kt` + `ui/sections/*.kt`, `ui/screens/HomeScreen.kt`, details in
  `LocationsScreen.kt` / `ScheduleScreen.kt`, `ui/components/EventInfo.kt`, `OptionsScreen.kt` (event rows only),
  `MapScreen.kt` (overlay flags), `ui/assistant/AssistantBlocksUi.kt` (shared pieces), `AttndApi.kt` (header).
- **Tests:** `DisplayLogic` cases for `PlaceEvents` / `PlaceWalkMap`; a decoding round trip of a server layout.

**Phase 5c — Catalogue-2 tab screens (M)**
- **Scope:** `schedule_tab`, `places_tab` and `updates_tab` from `TabListLayout` / `UpdatesLayout` (featured, pinned,
  groups); `onboarding` from `OnboardingLayout` (welcome, lock_screen cards); `update_banner` as a section on Home.
- **Files:** `ScheduleScreen.kt`, `LocationsScreen.kt`, `NotificationsScreen.kt`, `OnboardingScreen.kt`.
- **Tests:** in 5a's `ScreenLayoutTest`.

**Phase 6a — Assistant domain (L)** — ✅ done (Nov 22, 2026)
- **Shipped:** everything below except the `PlanDock` rules (`addToPlan` / `planAround` / `summaryLine`: deferred, the
  one-list spec (attnd-web `docs/specs/one_list_spec.md`) turns them into My schedule proposals). Tests:
  `ConversationContextTest` (10, iOS's 11 minus the sequence case, which is in `PlanProposalTest`),
  `AssistantToolsTest` (6), `AssistantRouterTest` (32 cases, iOS's), `PlanProposalTest`; the assistant eval
  (`AssistantEvalTest`: catalogue + 50 critical questions in every run; the full router run with `-PassistantEval`):
  **245/247** (iOS 242/247; Android passes "is the beer cold?", "cheapest food" and "bbq pitt lane" through the
  no-AI keyword search, and fails "what did I miss this morning" and "resttoom", as iOS). `TODO(one-list)` markers:
  `PlanCommands.sequence` (builds a Plan), the controller's SEQUENCE apply (`plans.applyPlan`), the router's
  `plan_edit` commands.
- **Scope:**
  - `ConversationContext`;
  - the router's My schedule grammar + `schedule_*` actions + app-settings commands (confirm chips);
  - `TokenBudget`, `assemble` / `answerText` / `namedEntities`;
  - the JSON tool protocol (`AssistantTools`: schema text, parse, validate through `ActionRegistry.check`, ≤ 4 rounds,
    two errors stop a tool);
  - sequences, `SwapCommand`, `PlanDock` rules;
  - `ChatTranscript`, `doneLine`, `looksForeign`;
  - `PlanTab.kt` renamed `PlanProposal.kt`.
- **Files:** A`domain/ConversationContext.kt` (new), `AssistantRouter.kt`, `AssistantActions.kt`, `AssistantModel.kt`,
  `AssistantFlow.kt`, `AssistantPrompt.kt` (context feeds retrieval), `PlanTab.kt` → `PlanProposal.kt`, new
  `AssistantTools.kt`.
- **Tests:** `ConversationContextTest` (11); `AssistantRouterTest`, `AssistantFlowTest` and `PlanTabTest` →
  `PlanProposalTest` re-synced to iOS; `PlanDockTest` (7); tool-protocol parser cases (new; mirror the iOS tool
  semantics).

**Phase 6b — My plan chat (L)**
- **Scope:**
  - the chat screen (bubbles, working line, done line, inline components, chips, proposal card, Undo card, Your Day);
  - transcript storage in files;
  - the model turn = the tool loop on `OnDeviceAssistant` (non-streaming `generateContent`), router fallback;
  - shared `/p/` plans as proposal cards;
  - live hints in the chat;
  - retire `MyPlanTab`, the dock, the answer card and `AssistantBarOverlay`.
- **Files:** A`ui/assistant/PlanChat.kt` (new), `PlanTab.kt` (deleted), `AssistantController.kt`, `AssistantBlocksUi.kt`,
  A`data/OnDeviceAssistant.kt`, A`data/PlanStore.kt` (files).
- **Tests:** domain covered; a controller test with a fake model (a JVM test of `AssistantController`'s turn path if it
  stays Android-free; else keep the logic in domain).

**Phase 7 — Themes, API-023 (S–M)**
- **Scope:** `ThemePalette` → Material colour schemes; dark / light by system; screenshot mode keeps the brand purple;
  `LiveThemeMapping` for the Live Update.
- **Files:** A`ui/theme/Theme.kt`, `Color.kt`, new `domain/Theme.kt`, `ui/AttndApp.kt` (one line: the theme passed in),
  `live/LiveNotifications.kt` (colour).
- **Tests:** theme decoding / derivation cases from iOS.

**Phase 8 — Live Update + reminders (M)** — ✅ done (Nov 22, 2026)
- **Scope:**
  - port `LiveActivityPlanner` (follow day with timed places, Now / Next always filled with event fallbacks, free
    until, gates, empty hint, no user position, relative countdowns > 1 h);
  - `live_activity` follows / buttons / stale text;
  - start on join (foreground);
  - Skip + Map + Dismiss actions;
  - `ReminderPlanner` (if not done in 3b).
- **Files:** A`domain/LiveFollow.kt`, A`live/LiveUpdateController.kt`, `LiveNotifications.kt`, `LiveUpdateReceiver.kt`.
- **Tests:** `LiveFollowTest` re-synced to the iOS 26 cases.
- **Built:** `domain/LiveFollow.kt` rewritten as iOS's `LiveActivityPlanner` (`followDay` with `FollowPlace`, `inputs`
  for `live_activity.follows`, `decision`, `content`, `decorate` (buttons, stale text, empty hint, Leave now), `staleAt`,
  `urgent`, `endedToday`) + `render` (the notification texts) + `nextWake` + per-entry `reminders`; the controller
  follows My schedule (`ScheduleController`), posts at once in the foreground, uses no position for the content;
  `PlanStore.follows` defaults on. `LiveFollowTest`: 24 tests (iOS cases that apply + Android's texts). Not drawn:
  `stale_text` (see CLAUDE.md, divergences).

**Phase 9 — Preview phones + jitter, API-026 (S–M)** — ✅ done (Nov 22, 2026)
- **Scope:**
  - `preview` kept by `JoinLinks` (App Links, QR, code field);
  - `preview_token` on join (redacted);
  - `preview` in the join answer → `DevicePrefs.previewEventId`;
  - `PreviewPoller` (`changes/` long-poll while started, `retry_after`, 15 s on errors, stops on `preview: false`,
    leave or switch);
  - never in screenshot mode.
- **Files:** A`domain/JoinLinks.kt`, `LogRedaction.kt`, A`data/api/AttndApi.kt` / `ApiDtos.kt`, new
  `data/PreviewPoller.kt`, `MainActivity.kt`, `EventRepository.kt`.
- **Tests:** `JoinLinksTest` (preview kept), a poll-policy test (pure), `LogRedactionTest` (token never logged).
- **Built:** `JoinLinks.previewToken` (join links only, iOS's token alphabet, ≤ 512), `PreviewPoll` (`next`, `runs`,
  `showsCapsule`, `afterJoin`) in `domain/PreviewPhone.kt`, `JoinRequest.previewToken`, `StatusResponseDto.preview`,
  `AttndApi.changes`, `EventRepository.previewEventId` (`DevicePrefs.previewEventId` in the non-backed-up device file;
  cleared by leave / switch / 404 / `preview: false`), `PreviewPoller` (started from `MainActivity`'s RESUMED block via
  `EventViewModel.previewSync`), the green "Instant updates on" pill in `EventStatusPills`; `Redact` masks
  `preview=` / `preview_token=`. Tests: `PreviewPhoneTest` (iOS's `testPreviewPoll` / `testPreviewToken_LinksAndJoinBody`
  cases + Android's).

**Phase 10a — Foldables: continuity + layouts (M)**
- **Scope:**
  - `configChanges`;
  - one map instance; pane leads, map trails;
  - `MapUiState`;
  - Home two columns + key-based scroll restore;
  - detail two columns;
  - `FoldAwareSplit.splitAt` into domain;
  - the Undo toast pane;
  - tabletop Home split (glance + list, no At the Event mode yet).
- **Files:** manifest, `ui/MapTab.kt`, `MapScreen.kt`, `HomeScreen.kt`, the detail screens,
  `ui/components/FoldAwareSplit.kt`, `domain/FoldRules.kt`.
- **Tests:** `FoldRules`, `HomeColumnWidths`, `DetailColumns`, `TabletopSplit` (from 5a), `splitAt`.

**Phase 10b — Foldables: differentiated features (L)**
- **Scope:**
  - At the Event mode + offer card;
  - time-travel map; linked panes;
  - stage vs stage; planning board;
  - drag and drop;
  - flyer + map with ML Kit text recognition;
  - `docs/FOLDABLE_CHECKLIST.md` from the current adaptive tiers;
  - foldable screenshots in `store-screenshots.yml`.
- **Files:** new A`ui/fold/*.kt`, `domain/FoldFeatures.kt`, `FlyerScreen.kt`, `MapScreen.kt`, `ScheduleScreen.kt`
  (detail pane), `PlanChat.kt` (board pane), `build.gradle` (text-recognition), `scripts/ci/screenshots.sh`.
- **Tests:** `FoldFeaturesTest` (iOS `DuoFeaturesTests` 13).

**Phase 11 — Performance, logging, server switch (M)**
- **Scope:**
  - `uiState` and search index on `Dispatchers.Default`;
  - the main-thread hang detector (`perf.hang`, DEV builds) + optional JankStats;
  - Trace sections;
  - the missing `LogEvent` names emitted at their sites;
  - `BuildConfig.UPLOAD_LOGS` (debug builds stop uploading);
  - the dev-build server switch;
  - map polish (cluster radius, follow modes, chip refit);
  - optional Baseline Profile.
- **Files:** `EventViewModel.kt`, `domain/LogEvents.kt`, `data/log/*`, `build.gradle`, `OptionsScreen.kt` (switch),
  `MapScreen.kt`.
- **Tests:** `LoggingTest` (names), a pure server-switch rule test.

**Phase 12 — Tracker and optional entries (S)**
- **Scope:**
  - tick API-018, 022, 023, 024, 026, 027, 030 and 031 as they ship (each phase does its own);
  - optional, only if the lead wants Android ahead of iOS: API-025 `event_full_free` message (tiny) and API-021
    `plan_suggestions` in onboarding / the empty chat.

### Parallel tracks (no shared files)

- **Wave 1:** **Phase 1 ∥ Phase 2.**
  - Both touch `build.gradle` and `AttndApplication.kt`, but in different blocks (1 adds a dependency and the device-id
    resolve; 2 removes dependencies and AppGraph entries).
  - If the agents can't rebase, run 2 first; it is mostly deletions.
- **Wave 2:**
  - Track A: Phase 3a ∥ Phase 5a (domain only, different files: 3a touches `MySchedule` / `Planner` / `Hearts` /
    `TabLayout`, 5a touches `ScreenLayout` / `Component` / `FoldRules` / `AssistantBlocks`).
  - In parallel with Phase 7 (theme files) and Phase 9 (join links, poller, API).
  - Phases 7 and 9 both touch `AttndApi.kt` / `ApiDtos.kt` only if 7 needs DTO work. It doesn't: phase 1 decodes the
    theme, so 7 is UI only.
- **Wave 3:**
  - Phase 4 (UI shell; needs 2 + 3a) ∥ Phase 6a (domain; needs 3a + 5a) ∥ Phase 8 (`live/` + `LiveFollow.kt`; needs 3a,
    and 5a for `LiveActivitySettings`).
  - 6a and 8 don't share files. 8 must not touch `MySchedule.kt` beyond reading it.
- **Wave 4:**
  - Phase 3b (schedule / places / map screens, heart button) ∥ Phase 5b (new sections, details, Home).
  - These overlap in `LocationsScreen.kt` / `ScheduleScreen.kt`: 3b owns the lists, 5b the detail panes. Split the
    detail composables into their own files (`LocationDetail.kt`, `ScheduleDetail.kt`) at the start of 5b, or run them
    one after the other.
- **Wave 5:** Phase 5c ∥ Phase 6b ∥ Phase 11 (11 avoids `MapScreen.kt` until 10a is done, or does its map polish last).
- **Wave 6:** Phase 10a, then 10b (both touch `MapScreen` / `MapTab` / `HomeScreen`).

**Critical path:** 2 → 3a → 4 → 5b → 6b → 10a → 10b, roughly 6–8 weeks of agent time if serial; less with the tracks
above.

---

## 6. Risks, and what needs a device

### Risks

1. **Gemini Nano limits.**
   - Input < 4,000 tokens per Google's docs; the beta may differ, so measure with `countTokens` if exposed.
   - AICore enforces a per-app inference quota and busy errors; every model failure must fall back to the router.
   - With iOS's 4,096-token window budget, Android's budget is tighter (no session reuse, the conversation is re-sent
     every tool round).
   - Tool rounds multiply latency (Nano ~1–3 s per call on a Pixel). Cap rounds at 4 and keep the working line honest.
   - Function calling exists only through the alpha `genai-schema`; our JSON protocol avoids it, but quality must be
     measured on a device.
2. **Few Android phones get the assistant** once it is hidden without Gemini Nano (§4.3). The planner, chat and
   onboarding plan card disappear for most attendees. Expected by the owner's rule; the lead should say so.
3. **Live Update promotion.**
   - Android 16 promotes ongoing notifications only if they meet its criteria: ongoing, a title, not colourised, no
     custom views; the user can turn promotion off per app.
   - Posting days ahead on join (the iOS "starts on join" rule) may be shown un-promoted or demoted by the system; check
     on a device.
   - Inexact `setWindow` alarms can be minutes late; Doze can defer further.
4. **Block Store.**
   - Data survives an uninstall / reinstall on the same device only while Play services keeps it.
   - Device-to-device restore needs a Google Account transfer.
   - `setShouldBackupToCloud(false)` mirrors iOS's this-device-only, so a new phone gets a new id, as on iOS. Check the
     current API name.
   - Google now pushes **Restore Credentials** (Credential Manager) for sign-in states; Block Store stays supported and
     fits a non-credential id. Re-check at implementation.
   - Can't be verified without a real device + reinstall; emulators with Play services work for the same-device case.
5. **`X-Attnd-Sections: 2` changes the ETag and returns `home` in `display.tabs`.** Enable it only with 5b (and the 5c
   fallbacks) in the same release. Older Android builds are unaffected (no header).
6. **Migration safety.**
   - My schedule's migration must be idempotent and keep the old hearts / plan keys readable. A rollback build would
     otherwise see empty likes.
   - Moving undo history and conversation from prefs to files needs a read-old-once path.
7. **Fold continuity.**
   - Declaring `configChanges` means resources aren't reloaded on size changes. Safe today (no size qualifiers); a
     future `values-w600dp` would silently not apply. Note it in `CLAUDE.md`.
   - `movableContentOf` with `GoogleMap` inside a custom `Layout` is unusual. Prototype it first in 10a and fall back to
     keeping the map always composed with a zero-size pane.
8. **Shared fixtures diverge further.** iOS and Android ports must stay 1:1. Every re-sync above copies the iOS JSON
   exactly. Where Android must differ (none planned beyond §4), note it in both `CLAUDE.md` files.
9. **Server switch on `debug` builds.** The static key only works on dev; the Production choice can't authenticate
   without Play Integrity. Show the switch only in `dev` builds, or warn.
10. **Live Google photos (API-031) and caching.** `HttpURLConnection` has no cache unless an `HttpResponseCache` is
    installed. Installing one globally would also cache API calls (`HttpClient.kt` relies on "no caching, so 304s reach
    us"). Use a memory-only path for these URLs instead.
11. **ML Kit text recognition** for the flyer chips adds ~4 MB (bundled model) or needs the unbundled Play services
    model. Choose the unbundled one.

### Can't be verified without a device

- Gemini Nano availability, download, quota, tool-loop quality and latency.
- Live Update promotion, chip text, actions and lock-screen look on Android 16+.
- Block Store across reinstall.
- FCM high-priority urgent delivery in Doze.
- Fold / unfold continuity on real hinges (emulators simulate postures but not hinge occlusion timing).
- Tabletop detection.
- Drag and drop feel.
- Compass heading cones.
- Play Integrity on the `dev` server switch.
- App Links verification for dev.attnd.app.
- The Tier 1 / Tier 2 checklist items that need a physical keyboard or mouse.

Phase 10b's checklist should list these as manual rows, like iOS's `IPHONE_DUO_CHECKLIST.md`.

### Web sources checked (Nov 22, 2026)

- Google Play Instant shut down December 2025 (Android Authority, heise; Google's `instantapps` reference carries the
  deprecation): https://androidauthority.com/google-killing-android-instant-apps-3567211, https://heise.de/-10447201
- Adaptive app quality guidelines (tiers, April 2027 foldable requirement, test devices):
  https://developer.android.com/docs/quality-guidelines/large-screen-app-quality,
  https://developer.android.com/blog/posts/google-play-adaptive-quality-standards
- ML Kit Prompt API (input < 4,000 tokens; function calling via `genai-schema` alpha):
  https://developers.google.com/ml-kit/genai/prompt/android/get-started,
  https://developers.google.com/ml-kit/genai/prompt/android/function-calling (search results only; developers.google.com
  isn't reachable from this session)
- Block Store and Restore Credentials: https://developer.android.com/identity/block-store,
  https://developer.android.com/distribute/aep/aep-req-restore-credentials
