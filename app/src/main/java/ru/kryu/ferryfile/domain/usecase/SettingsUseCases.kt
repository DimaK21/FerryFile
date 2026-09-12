package ru.kryu.ferryfile.domain.usecase

import kotlinx.coroutines.flow.StateFlow
import ru.kryu.ferryfile.domain.model.Port
import ru.kryu.ferryfile.domain.model.SharedFolder
import ru.kryu.ferryfile.domain.repository.SettingsRepository
import javax.inject.Inject

class ObservePortUseCase @Inject constructor(private val settings: SettingsRepository) {
    operator fun invoke(): StateFlow<Port> = settings.port
}

class ObserveDarkThemeUseCase @Inject constructor(private val settings: SettingsRepository) {
    operator fun invoke(): StateFlow<Boolean> = settings.darkTheme
}

class ObserveSharedFoldersUseCase @Inject constructor(private val settings: SettingsRepository) {
    operator fun invoke(): StateFlow<List<SharedFolder>> = settings.sharedFolders
}

class SetPortUseCase @Inject constructor(private val settings: SettingsRepository) {

    enum class Result { Saved, OutOfRange }

    suspend operator fun invoke(text: String): Result {
        val port = Port.parse(text) ?: return Result.OutOfRange
        settings.setPort(port)
        return Result.Saved
    }
}

class SetDarkThemeUseCase @Inject constructor(private val settings: SettingsRepository) {
    suspend operator fun invoke(enabled: Boolean) = settings.setDarkTheme(enabled)
}

class AddSharedFolderUseCase @Inject constructor(private val settings: SettingsRepository) {
    suspend operator fun invoke(uri: String): Boolean = settings.addSharedFolder(uri)
}

class RemoveSharedFolderUseCase @Inject constructor(private val settings: SettingsRepository) {
    suspend operator fun invoke(uri: String) = settings.removeSharedFolder(uri)
}
