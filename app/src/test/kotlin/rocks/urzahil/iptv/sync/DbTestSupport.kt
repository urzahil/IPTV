package rocks.urzahil.iptv.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import rocks.urzahil.iptv.data.db.AppDatabase
import rocks.urzahil.iptv.data.db.entities.SourceEntity
import rocks.urzahil.iptv.data.db.entities.SourceType
import rocks.urzahil.iptv.data.security.SecretCipher
import kotlinx.coroutines.runBlocking

/** Reversible stand-in for the Keystore cipher. */
class FakeCipher : SecretCipher {
    override fun encrypt(plain: String) = "enc:" + plain.reversed()
    override fun decrypt(token: String): String? = token.removePrefix("enc:").reversed().takeIf { token.startsWith("enc:") }
}

fun newDb(): AppDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()

fun AppDatabase.addSource(type: SourceType = SourceType.M3U_URL, name: String = "S"): SourceEntity = runBlocking {
    val id = sourceDao().insert(SourceEntity(type = type.name, name = name))
    sourceDao().get(id)!!
}
