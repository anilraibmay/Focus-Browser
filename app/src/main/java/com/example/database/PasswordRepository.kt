package com.example.database

import kotlinx.coroutines.flow.Flow

class PasswordRepository(private val passwordDao: PasswordDao) {
    val allPasswordsFlow: Flow<List<PasswordEntity>> = passwordDao.getAllPasswords()

    suspend fun getPasswordsForDomain(domain: String): List<PasswordEntity> {
        return passwordDao.getPasswordsForDomain(domain)
    }

    suspend fun insertPassword(password: PasswordEntity) {
        passwordDao.insertPassword(password)
    }

    suspend fun deletePassword(password: PasswordEntity) {
        passwordDao.deletePassword(password)
    }
}
