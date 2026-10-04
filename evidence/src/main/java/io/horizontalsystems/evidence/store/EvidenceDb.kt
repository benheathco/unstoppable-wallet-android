package io.horizontalsystems.evidence.store

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [CaptureEntity::class, ArtifactEntity::class], version = 1)
abstract class EvidenceDb : RoomDatabase() {
    abstract fun dao(): EvidenceDao

    companion object {
        @Volatile
        private var instance: EvidenceDb? = null

        fun get(context: Context): EvidenceDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, EvidenceDb::class.java, "evidence.db")
                .build()
                .also { instance = it }
        }
    }
}
