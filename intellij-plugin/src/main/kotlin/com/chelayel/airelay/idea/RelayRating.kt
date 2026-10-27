package com.chelayel.airelay.idea

import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A one-time ask for a Marketplace rating, made only once the plugin has earned
 * it: after a week of use and a good number of finished turns. "Later" (or
 * closing the balloon) asks again in a month; rating, the coffee link or "Don't
 * ask again" end it. The same rules as the VS Code extension's.
 */
object RelayRating {
    const val REVIEW_URL = "https://plugins.jetbrains.com/plugin/34342-ai-relay/reviews"
    const val COFFEE_URL = "https://buymeacoffee.com/chelayel"

    private val DAY = TimeUnit.DAYS.toMillis(1)
    private const val MIN_DAYS = 7
    private const val MIN_TURNS = 20
    private const val LATER_DAYS = 30

    private const val FIRST_SEEN = "airelay.rating.firstSeen"
    private const val TURNS = "airelay.rating.turns"
    private const val NEXT_ASK = "airelay.rating.nextAsk"
    private const val DONE = "airelay.rating.done"

    private val asking = AtomicBoolean(false)

    /** Counts a finished turn, and asks for a rating when the time has come. */
    fun noteTurn(project: Project, now: Long = System.currentTimeMillis()) {
        val props = PropertiesComponent.getInstance()
        if (props.getBoolean(DONE)) return
        val firstSeen = props.getValue(FIRST_SEEN)?.toLongOrNull() ?: now.also { props.setValue(FIRST_SEEN, it.toString()) }
        val turns = props.getInt(TURNS, 0) + 1
        props.setValue(TURNS, turns, 0)
        val nextAsk = props.getValue(NEXT_ASK)?.toLongOrNull() ?: 0
        if (turns < MIN_TURNS || now - firstSeen < MIN_DAYS * DAY || now < nextAsk) return
        if (!asking.compareAndSet(false, true)) return
        ask(project, props, now)
    }

    private fun ask(project: Project, props: PropertiesComponent, now: Long) {
        var answered = false
        fun finish(n: Notification, done: Boolean) {
            answered = true
            if (done) props.setValue(DONE, true) else props.setValue(NEXT_ASK, (now + LATER_DAYS * DAY).toString())
            n.expire()
        }
        val notification = NotificationGroupManager.getInstance().getNotificationGroup("AI Relay")
            .createNotification("Enjoying AI Relay?", "A rating on the JetBrains Marketplace helps others find it. It takes a minute.", NotificationType.INFORMATION)
            .addAction(NotificationAction.create("Rate AI Relay") { _, n -> BrowserUtil.browse(REVIEW_URL); finish(n, true) })
            .addAction(NotificationAction.create("Buy me a coffee") { _, n -> BrowserUtil.browse(COFFEE_URL); finish(n, true) })
            .addAction(NotificationAction.create("Later") { _, n -> finish(n, false) })
            .addAction(NotificationAction.create("Don't ask again") { _, n -> finish(n, true) })
        // Closed without an answer: it was seen, not answered, so it comes back later.
        notification.whenExpired {
            if (!answered) props.setValue(NEXT_ASK, (now + LATER_DAYS * DAY).toString())
            asking.set(false)
        }
        Notifications.Bus.notify(notification, project)
    }
}

/** Find Action → "AI Relay: Buy Me a Coffee". */
class BuyMeACoffeeAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) = BrowserUtil.browse(RelayRating.COFFEE_URL)
}
