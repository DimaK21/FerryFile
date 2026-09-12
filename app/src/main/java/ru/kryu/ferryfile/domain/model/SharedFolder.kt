package ru.kryu.ferryfile.domain.model

/** Папка, к которой пользователь выдал доступ через SAF. */
data class SharedFolder(val uri: String, val displayName: String)
