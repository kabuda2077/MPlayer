package com.theveloper.pixelplay.data.backup.module

import com.google.gson.Gson
import com.theveloper.pixelplay.data.backup.model.BackupSection
import com.theveloper.pixelplay.data.netease.NeteaseRepository
import com.theveloper.pixelplay.data.preferences.AiPreferencesRepository
import com.theveloper.pixelplay.data.preferences.PreferenceBackupEntry
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.qqmusic.QqMusicRepository
import com.theveloper.pixelplay.di.BackupGson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SensitiveDataModuleHandler @Inject constructor(
    private val neteaseRepository: NeteaseRepository,
    private val qqMusicRepository: QqMusicRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    @BackupGson private val gson: Gson
) : BackupModuleHandler {

    override val section = BackupSection.SENSITIVE_DATA

    override suspend fun export(): String = withContext(Dispatchers.IO) {
        gson.toJson(buildPayload())
    }

    override suspend fun countEntries(): Int = withContext(Dispatchers.IO) {
        val payload = buildPayload()
        listOfNotNull(payload.netease, payload.qqMusic).size + payload.aiPreferences.size
    }

    override suspend fun snapshot(): String = export()

    override suspend fun restore(payload: String) = withContext(Dispatchers.IO) {
        val parsed = gson.fromJson(payload, SensitiveDataBackupPayload::class.java)
            ?: SensitiveDataBackupPayload()
        neteaseRepository.restoreAuthBackupSnapshot(parsed.netease)
        qqMusicRepository.restoreAuthBackupSnapshot(parsed.qqMusic)

        val aiEntries = parsed.aiPreferences
            .filter { AiPreferencesRepository.isSensitiveBackupKey(it.key) }
        userPreferencesRepository.clearPreferencesByKeys(AiPreferencesRepository.SENSITIVE_BACKUP_KEY_NAMES)
        if (aiEntries.isNotEmpty()) {
            userPreferencesRepository.importPreferencesFromBackup(aiEntries, clearExisting = false)
        }
    }

    override suspend fun rollback(snapshot: String) = restore(snapshot)

    private suspend fun buildPayload(): SensitiveDataBackupPayload {
        val aiPreferences = userPreferencesRepository.exportPreferencesForBackup()
            .filter { AiPreferencesRepository.isSensitiveBackupKey(it.key) }
        return SensitiveDataBackupPayload(
            netease = neteaseRepository.exportAuthBackupSnapshot(),
            qqMusic = qqMusicRepository.exportAuthBackupSnapshot(),
            aiPreferences = aiPreferences
        )
    }

    private data class SensitiveDataBackupPayload(
        val netease: NeteaseRepository.AuthBackupSnapshot? = null,
        val qqMusic: QqMusicRepository.AuthBackupSnapshot? = null,
        val aiPreferences: List<PreferenceBackupEntry> = emptyList()
    )
}
