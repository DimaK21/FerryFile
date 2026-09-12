package ru.kryu.ferryfile.domain.repository

interface NetworkRepository {

    /** IPv4-адрес устройства в локальной сети или `null`, если сети нет. */
    suspend fun localAddress(): String?
}
