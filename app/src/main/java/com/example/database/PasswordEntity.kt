package com.example.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(tableName = "passwords")
data class PasswordEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val domain: String,
    val username: String, // Plain text
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val encryptedPassword: ByteArray,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val initializationVector: ByteArray,
    val createdAtMs: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PasswordEntity

        if (id != other.id) return false
        if (domain != other.domain) return false
        if (username != other.username) return false
        if (!encryptedPassword.contentEquals(other.encryptedPassword)) return false
        if (!initializationVector.contentEquals(other.initializationVector)) return false
        if (createdAtMs != other.createdAtMs) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + domain.hashCode()
        result = 31 * result + username.hashCode()
        result = 31 * result + encryptedPassword.contentHashCode()
        result = 31 * result + initializationVector.contentHashCode()
        result = 31 * result + createdAtMs.hashCode()
        return result
    }
}
