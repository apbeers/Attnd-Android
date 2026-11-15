package com.arcu.attnd.unit

import com.arcu.attnd.domain.AssistantContext
import com.arcu.attnd.domain.GeoPoint
import com.arcu.attnd.domain.Hearts
import com.arcu.attnd.domain.MealBreak
import com.arcu.attnd.domain.Plan
import com.arcu.attnd.domain.PlanPreferences
import com.arcu.attnd.domain.PlannedItem
import com.arcu.attnd.domain.Planner
import com.arcu.attnd.unit.PlannerFixture.bundle
import com.arcu.attnd.unit.PlannerFixture.hearts
import com.arcu.attnd.unit.PlannerFixture.now
import java.time.ZoneId

/**
 * The event assistant's fixture (assistant spec §9; iOS uses the same): [PlannerFixture] plus the
 * planner test's "Plan my Saturday around live music and BBQ" plan, made at Sat 10:00 AM with the
 * fixture's likes (101, 114):
 * 10:00 111 · 11:00 109 · 12:00 lunch · 13:00 102 · 15:00 103 · 17:00 105 · 18:15 113 · 19:00 101 · 21:00 104.
 */
object AssistantFixture {
    val zone: ZoneId = ZoneId.of("America/Chicago")
    val musicAndBbq = PlanPreferences(likedTags = setOf("live music", "bbq"), includeMeals = true)
    val plan: Plan = Planner(bundle, musicAndBbq, hearts).plan(now, "Plan my Saturday around live music and BBQ")

    /** At the Main Stage (location 1). */
    val atMainStage: GeoPoint = bundle.location(1)!!.coordinate!!

    fun ctx(hearts: Hearts = PlannerFixture.hearts, plan: Plan? = this.plan, user: GeoPoint? = null) =
        AssistantContext(bundle, hearts, plan, now, user = user)

    /** "10:00 111", "12:00 lunch", … in the event's zone. */
    fun clock(plan: Plan): List<String> = plan.days.flatMap { day ->
        day.entries.map { entry ->
            val time = entry.start.atZone(zone).toLocalTime().toString()
            when (entry) {
                is PlannedItem -> "$time ${entry.itemId}"
                is MealBreak -> "$time ${entry.kind.name.lowercase()}"
            }
        }
    }
}
