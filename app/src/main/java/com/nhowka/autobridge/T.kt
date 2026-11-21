package com.nhowka.autobridge

import java.util.Locale

/** English / Brazilian Portuguese, chosen by the device language (phones and the head unit alike). */
object T {
    val pt: Boolean get() = Locale.getDefault().language == "pt"
    fun s(en: String, pt: String): String = if (this.pt) pt else en
}
