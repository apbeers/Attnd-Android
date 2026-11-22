package com.arcu.attnd.domain

/**
 * The diagnostics event names the Android app emits (logging spec §1). Every one is in attnd-web's shared
 * catalogue `LOG_EVENTS.md` with its category, level and fields; add a row there before logging a new name,
 * and never rename or reuse one (old builds keep sending it). `LoggingTest` checks the shape.
 */
object LogEvent {
    // lifecycle
    const val LAUNCH = "lifecycle.launch"
    const val FOREGROUND = "lifecycle.foreground"
    const val BACKGROUND = "lifecycle.background"
    const val MEMORY_WARNING = "lifecycle.memory_warning"
    const val EXIT_REASON = "lifecycle.exit_reason"
    const val UPDATE_PROMPT = "lifecycle.update_prompt"
    const val DEEP_LINK = "lifecycle.deep_link"
    const val EVENT_JOINED = "lifecycle.event.joined"
    const val EVENT_LEFT = "lifecycle.event.left"
    const val SERVER_SWITCH = "lifecycle.server_switch"

    // net
    const val REQUEST_STARTED = "net.request.started"
    const val REQUEST_FINISHED = "net.request.finished"
    const val REQUEST_FAILED = "net.request.failed"
    const val REQUEST_RETRY = "net.request.retry"
    const val BUNDLE_APPLIED = "net.bundle.applied"
    const val BUNDLE_NOT_MODIFIED = "net.bundle.not_modified"
    const val POLL_SCHEDULED = "net.poll.scheduled"
    const val REACHABILITY = "net.reachability"
    const val OFFLINE_NOTICE = "net.offline_notice"
    const val IMAGE_FETCHED = "net.image.fetched"
    const val IMAGE_FAILED = "net.image.failed"
    const val ROUTE_FETCHED = "net.route.fetched"
    const val USAGE_FLUSHED = "net.usage.flushed"
    const val DEVICE_REGISTERED = "net.device.registered"
    const val APP_STATUS = "net.app_status"

    // push
    const val PUSH_TOKEN = "push.token"
    const val PUSH_TOKEN_FAILED = "push.token.failed"
    const val PUSH_PERMISSION = "push.permission"
    const val PUSH_RECEIVED = "push.received"
    const val PUSH_HANDLED = "push.handled"
    const val PUSH_DROPPED = "push.dropped"
    const val PUSH_OPENED = "push.opened"

    // background
    const val WAKE = "background.wake"
    const val REFRESH_STARTED = "background.refresh.started"
    const val REFRESH_FINISHED = "background.refresh.finished"
    const val REFRESH_FAILED = "background.refresh.failed"

    // live (Live Update, reminders; live.arrival stays in LOG_EVENTS.md, no longer emitted)
    const val LIVE_START = "live.start"
    const val LIVE_START_FAILED = "live.start.failed"
    const val LIVE_UPDATE = "live.update"
    const val LIVE_END = "live.end"
    const val LIVE_ACTION = "live.action"
    const val LIVE_WAKE_SCHEDULED = "live.wake.scheduled"
    const val LIVE_REMINDER_SHOWN = "live.reminder.shown"
    const val LIVE_ARRIVAL = "live.arrival"
    const val LIVE_FOLLOW_CHANGED = "live.follow.changed"
    const val LIVE_RUNNING_LATE = "live.running_late"
    const val LIVE_CHECKOFF = "live.checkoff"
    const val LIVE_REMINDER_SCHEDULED = "live.reminder.scheduled"

    // location
    const val LOCATION_PERMISSION = "location.permission"
    const val LOCATION_UPDATES = "location.updates"
    const val HEADING_SOURCE = "location.heading.source"
    const val LOCATION_ERROR = "location.error"
    const val LOCATION_FIX = "location.fix"

    // map
    const val MAP_FIRST_RENDER = "map.first_render"
    const val MAP_FIT = "map.fit"
    const val MAP_PIN_SELECTED = "map.pin.selected"
    const val MAP_ROUTE_SHOWN = "map.route.shown"
    const val MAP_TILES_ERROR = "map.tiles.error"

    // planner
    const val PLANNER_REQUEST = "planner.request"
    const val PLANNER_EXTRACT = "planner.extract"
    const val PLANNER_RUN = "planner.run"
    const val PLANNER_SWAP = "planner.swap"
    const val PLANNER_AVAILABILITY = "planner.availability"
    const val PLANNER_CHANGED = "planner.changed"

    // Logged under the planner category (attnd-web LOG_EVENTS.md).
    const val PLAN_EDIT = "plan.edit"
    const val PLAN_UNDO = "plan.undo"

    // assistant (on-device model, router)
    const val ASSISTANT_REQUEST = "assistant.request"
    const val MODEL_STATUS = "assistant.model.status"
    const val MODEL_DOWNLOAD = "assistant.model.download"
    const val ASSISTANT_ANSWER = "assistant.answer"
    const val ASSISTANT_ERROR = "assistant.error"
    const val ASSISTANT_INDEX = "assistant.index"
    const val ASSISTANT_TURN = "assistant.turn"
    const val ASSISTANT_BLOCK = "assistant.block"
    const val ASSISTANT_ACTION = "assistant.action"

    // search
    const val SEARCH_QUERY = "search.query"
    const val SEARCH_OPEN = "search.open"

    // storage
    const val BUNDLE_SAVED = "storage.bundle.saved"
    const val BUNDLE_RESTORED = "storage.bundle.restored"
    const val BUNDLE_FAILED = "storage.bundle.failed"
    const val IMAGES_PREFETCH = "storage.images.prefetch"
    const val PREFS_MIGRATED = "storage.prefs.migrated"
    const val IMAGES_PRUNED = "storage.images.pruned"

    // ui
    const val SCREEN = "ui.screen"
    const val UI_ACTION = "ui.action"
    const val UI_ERROR_SHOWN = "ui.error.shown"
    const val UI_BANNER = "ui.banner"

    // Logged under the ui category (attnd-web LOG_EVENTS.md).
    const val SHARE_CREATED = "share.created"
    const val SHARE_OPENED = "share.opened"

    // perf
    const val SIGNPOST = "perf.signpost"
    const val PERF_METRIC = "perf.metric"
    const val PERF_HANG = "perf.hang"
    const val PERF_FRAME_DROPS = "perf.frame_drops"

    // auth
    const val ATTEST_STARTED = "auth.attest.started"
    const val ATTEST_FINISHED = "auth.attest.finished"
    const val ATTEST_FAILED = "auth.attest.failed"
    const val TOKEN_EXPIRED = "auth.token.expired"

    // logging
    const val DEBUG_MODE = "logging.debug_mode"
    const val UPLOAD = "logging.upload"
    const val UPLOAD_FAILED = "logging.upload.failed"
    const val DROPPED = "logging.dropped"
    const val EXPORT = "logging.export"
    const val REPORT_SENT = "logging.report.sent"
    const val RING_TRIMMED = "logging.ring.trimmed"

    /**
     * The `name` values of `perf.signpost` (LOG_EVENTS.md): the `android.os.Trace` sections [AppLog.section] /
     * [AppLog.asyncSection] open. `perf.hang` reports the one running when the main thread stalled.
     */
    val SIGNPOSTS: List<String> = listOf(
        "launch", "bundle_fetch", "bundle_decode", "bundle_apply", "bundle_save", "planner", "map_first_render", "search_index",
    )

    // errors (any category)
    const val ERROR_CAUGHT = "error.caught"

    val ALL: List<String> = listOf(
        LAUNCH, FOREGROUND, BACKGROUND, MEMORY_WARNING, EXIT_REASON, UPDATE_PROMPT, DEEP_LINK, EVENT_JOINED, EVENT_LEFT, SERVER_SWITCH,
        REQUEST_STARTED, REQUEST_FINISHED, REQUEST_FAILED, REQUEST_RETRY, BUNDLE_APPLIED, BUNDLE_NOT_MODIFIED,
        POLL_SCHEDULED, REACHABILITY, OFFLINE_NOTICE, IMAGE_FETCHED, IMAGE_FAILED, ROUTE_FETCHED, USAGE_FLUSHED, DEVICE_REGISTERED, APP_STATUS,
        PUSH_TOKEN, PUSH_TOKEN_FAILED, PUSH_PERMISSION, PUSH_RECEIVED, PUSH_HANDLED, PUSH_DROPPED, PUSH_OPENED,
        WAKE, REFRESH_STARTED, REFRESH_FINISHED, REFRESH_FAILED,
        LIVE_START, LIVE_START_FAILED, LIVE_UPDATE, LIVE_END, LIVE_ACTION, LIVE_WAKE_SCHEDULED, LIVE_REMINDER_SHOWN,
        LIVE_ARRIVAL, LIVE_FOLLOW_CHANGED, LIVE_RUNNING_LATE, LIVE_CHECKOFF, LIVE_REMINDER_SCHEDULED,
        LOCATION_PERMISSION, LOCATION_UPDATES, HEADING_SOURCE, LOCATION_ERROR, LOCATION_FIX,
        MAP_FIRST_RENDER, MAP_FIT, MAP_PIN_SELECTED, MAP_ROUTE_SHOWN, MAP_TILES_ERROR,
        PLANNER_REQUEST, PLANNER_EXTRACT, PLANNER_RUN, PLANNER_SWAP, PLANNER_AVAILABILITY, PLANNER_CHANGED, PLAN_EDIT, PLAN_UNDO,
        ASSISTANT_REQUEST, MODEL_STATUS, MODEL_DOWNLOAD, ASSISTANT_ANSWER, ASSISTANT_ERROR, ASSISTANT_INDEX, ASSISTANT_TURN, ASSISTANT_BLOCK, ASSISTANT_ACTION,
        SEARCH_QUERY, SEARCH_OPEN,
        BUNDLE_SAVED, BUNDLE_RESTORED, BUNDLE_FAILED, IMAGES_PREFETCH, PREFS_MIGRATED, IMAGES_PRUNED,
        SCREEN, UI_ACTION, UI_ERROR_SHOWN, UI_BANNER, SHARE_CREATED, SHARE_OPENED,
        SIGNPOST, PERF_METRIC, PERF_HANG, PERF_FRAME_DROPS,
        ATTEST_STARTED, ATTEST_FINISHED, ATTEST_FAILED, TOKEN_EXPIRED,
        DEBUG_MODE, UPLOAD, UPLOAD_FAILED, DROPPED, EXPORT, REPORT_SENT, RING_TRIMMED,
        ERROR_CAUGHT,
    )
}
