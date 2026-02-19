package com.aura.link

import android.content.Context

fun String?.toGender(): Gender {
    return when (this?.uppercase()) {
        "M", "MALE", "ERKEK" -> Gender.MALE
        "F", "FEMALE", "KADIN" -> Gender.FEMALE
        else -> Gender.UNKNOWN // Default to UNKNOWN instead of MALE
    }
}

fun Gender.toDisplayString(context: Context): String {
    return when (this) {
        Gender.MALE -> context.getString(R.string.male_gender)
        Gender.FEMALE -> context.getString(R.string.female_gender)
        Gender.UNKNOWN -> context.getString(R.string.unknown_gender)
    }
}

fun Gender.toDisplayString(): String {
    return when (this) {
        Gender.MALE -> "Male"
        Gender.FEMALE -> "Female"
        Gender.UNKNOWN -> "Unknown"
    }
}

fun Gender.toEnglishString(): String {
    return when (this) {
        Gender.MALE -> "Male"
        Gender.FEMALE -> "Female"
        Gender.UNKNOWN -> "Unknown"
    }
}