package com.yolo.detector.data

/**
 * User-configurable email-alert settings.
 *
 * The provider API key is stored separately and encrypted (see [EmailSettingsRepository]);
 * everything here is low-sensitivity operational configuration.
 *
 * @param enabled       master switch: when false no alerts are dispatched.
 * @param recipient     destination email address.
 * @param senderEmail   address that must be verified in the provider account;
 *                      falls back to [recipient] when blank.
 * @param cooldownMs    quiet period between alerts (30–120 s typical).
 * @param triggerClassIds empty = alert on any detection; otherwise restrict alerts
 *                        to these COCO class IDs.
 */
data class EmailSettings(
    val enabled: Boolean = false,
    val recipient: String = "",
    val senderEmail: String = "",
    val cooldownMs: Long = 60_000L,
    val triggerClassIds: Set<Int> = emptySet(),
)