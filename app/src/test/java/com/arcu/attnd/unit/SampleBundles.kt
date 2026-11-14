package com.arcu.attnd.unit

/** Bundle payloads modelled on the examples in API_V2.md. */
object SampleBundles {

    /** A complete v2 bundle, plus keys this client doesn't know about (which must be ignored). */
    const val FULL = """
    {
      "schema_version": 2,
      "content_version": 15,
      "updated_at": "2026-11-14T13:29:44+00:00",
      "some_future_key": {"nested": [1, 2, 3]},
      "event": {
        "id": 1, "name": "Fort Worth Food & Music Festival", "event_code": "FWFM2026",
        "description": "Food and music downtown.", "address": "Sundance Square, Fort Worth, TX",
        "latitude": 32.75, "longitude": -97.33,
        "start_time": "2026-11-14T16:00:00+00:00", "end_time": "2026-11-16T04:00:00+00:00",
        "timezone": "America/Chicago",
        "status": "published", "paid": false, "is_demo": true,
        "flyer_url": "https://attnd.app/media/flyer.png",
        "accent_color": "#FF5A1F", "logo_url": "https://attnd.app/media/logo.png",
        "content_version": 15, "join_url": "https://attnd.app/join/FWFM2026/",
        "organizer_note": "unknown key"
      },
      "display": {
        "tabs": ["schedule", "map", "locations", "notifications", "flyer", "tickets"],
        "default_tab": "schedule",
        "locations_style": "grouped",
        "schedule_style": "now_next",
        "map_clustering": true,
        "map_fit_all": false,
        "show_location_images": false,
        "enable_search": false,
        "group_by_category": true,
        "secondary_pane": "now_next",
        "accent_color": "#123456",
        "logo_url": null,
        "preset": "festival", "resolved_preset": "festival", "reason": "size", "stats": {"locations": 3}, "schema_version": 2
      },
      "categories": ["Food", "Stage"],
      "locations": [
        {"id": 5, "name": "Taco Truck", "snippet": "Tacos", "description": "Best tacos", "category": "Food",
         "website": null, "image_url": "https://img/taco.jpg", "address": "1 Main St",
         "latitude": 32.74, "longitude": -97.36, "sort_order": 0},
        {"id": 6, "name": "Main Stage", "snippet": "", "description": "", "category": "Stage",
         "website": "https://stage.example", "image_url": null, "address": "",
         "latitude": null, "longitude": null, "sort_order": 1},
        {"id": 7, "name": "Info Booth", "category": null, "latitude": 0.0, "longitude": 0.0, "sort_order": 2}
      ],
      "schedule": [
        {"id": 1, "name": "Opening", "description": "Doors open", "start_time": "2026-11-14T16:00:00+00:00",
         "end_time": "2026-11-14T17:00:00+00:00", "location_id": 6, "location_name": "Main Stage", "tags": ["Music"]},
        {"id": 2, "name": "Late set", "description": "", "start_time": "2026-11-15T03:30:00+00:00",
         "end_time": null, "location_id": null, "location_name": null, "tags": []}
      ],
      "notifications": [
        {"id": 9, "title": "Gates open", "body": "Come on in", "send_time": "2026-11-14T15:55:00+00:00"}
      ]
    }
    """

    /** Bare minimum: an event with only an id, no display object, nulls and missing lists. */
    const val MINIMAL = """
    {
      "event": {"id": 3, "name": "Pop-up", "latitude": null, "longitude": null, "timezone": null,
                "paid": true, "flyer_url": null},
      "locations": null,
      "schedule": [{"id": 4, "name": "Thing"}, {"name": "no id, skipped"}]
    }
    """

    /** A display object with only some keys present. */
    const val PARTIAL_DISPLAY = """
    {
      "event": {"id": 2, "name": "Expo", "flyer_url": "https://attnd.app/f.pdf", "status": "archived", "paid": true, "is_demo": false},
      "display": {"schedule_style": "by_location", "tabs": ["nonsense"], "default_tab": "flyer", "secondary_pane": "sideways"}
    }
    """
}
