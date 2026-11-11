package io.github.urzahil.iptv.sync

import org.robolectric.RobolectricTestRunner
import com.google.common.truth.Truth.assertThat
import io.github.urzahil.iptv.data.db.AppDatabase
import io.github.urzahil.iptv.data.db.entities.FavouriteEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class M3uImporterTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() { db = newDb() }
    @After fun tearDown() { db.close() }

    private val playlist = """
        #EXTM3U url-tvg="http://epg.example/guide.xml.gz"
        #EXTINF:-1 tvg-id="a" group-title="News;Sport",Alpha
        http://s/a.m3u8
        #EXTINF:-1 group-title="Sport",Beta
        http://s/b.m3u8
        #EXTINF:-1,No Group
        http://s/c.m3u8
        #EXTINF:-1 group-title="Kids",Delta
        http://s/d.m3u8
        #EXTINF:-1 group-title="News",Echo
        http://s/e.m3u8
    """.trimIndent()

    private fun import(src: io.github.urzahil.iptv.data.db.entities.SourceEntity, text: String) =
        M3uImporter(db).import(src, text.byteInputStream())

    @Test fun groupsKeepProviderOrderAndMultiGroupMembership() {
        val src = db.addSource()
        val r = import(src, playlist)
        assertThat(r.channels).isEqualTo(5)
        assertThat(r.epgUrls).containsExactly("http://epg.example/guide.xml.gz")

        val cats = db.categoryDao().getAllBlocking(src.id)
        assertThat(cats.map { it.name }).containsExactly("News", "Sport", "Uncategorized", "Kids").inOrder()
        assertThat(cats.associate { it.name to it.channelCount }).containsExactly("News", 2, "Sport", 2, "Uncategorized", 1, "Kids", 1)

        val news = cats.first { it.name == "News" }
        val ids = runBlocking {
            val out = ArrayList<Long>()
            var cur = db.channelDao().firstIn(news.id)
            while (cur != null) {
                out.add(cur)
                val pos = db.channelDao().positionIn(news.id, cur)!!
                cur = db.channelDao().nextAfter(news.id, pos)
            }
            out
        }
        val names = ids.map { id -> runBlocking { db.channelDao().get(id)!!.name } }
        assertThat(names).containsExactly("Alpha", "Echo").inOrder()
    }

    @Test fun reimportKeepsIdsAndFavouritesAndRemovesStale() {
        val src = db.addSource()
        import(src, playlist)
        val alpha = runBlocking { db.channelDao().keyRows(src.id).first { it.providerKey.contains("|Alpha|") } }
        runBlocking { db.favouriteDao().insert(FavouriteEntity(alpha.id, 1)) }

        val second = """
            #EXTM3U
            #EXTINF:-1 tvg-id="a" group-title="News",Alpha
            http://s/a2.m3u8
            #EXTINF:-1 group-title="Movies",Zulu
            http://s/z.m3u8
        """.trimIndent()
        import(db.sourceDao().getBlocking(src.id)!!, second)

        val keys = db.channelDao().keyRows(src.id)
        assertThat(keys).hasSize(2)
        assertThat(keys.first { it.providerKey.contains("|Alpha|") }.id).isEqualTo(alpha.id)
        assertThat(runBlocking { db.favouriteDao().isFavourite(alpha.id) }).isEqualTo(1)
        val cats = db.categoryDao().getAllBlocking(src.id).map { it.name }
        assertThat(cats).containsExactly("News", "Movies").inOrder()
        assertThat(db.sourceDao().getBlocking(src.id)!!.syncGen).isEqualTo(2)
        // Alpha left the Sport group: its membership is gone.
        assertThat(db.categoryDao().getAllBlocking(src.id).sumOf { it.channelCount }).isEqualTo(2)
    }

    @Test fun searchFindsByPrefixAndDiacritics() {
        val src = db.addSource()
        import(src, "#EXTM3U\n#EXTINF:-1,Das Erste HD\nhttp://s/1\n#EXTINF:-1,Café Télé\nhttp://s/2\n")
        runBlocking {
            assertThat(db.channelDao().searchFts("ers*", false).map { it.name }).isEmpty()
            assertThat(db.channelDao().searchFts("er*", true).map { it.name }).containsExactly("Das Erste HD")
            assertThat(db.channelDao().searchFts("cafe* tele*", true).map { it.name }).containsExactly("Café Télé")
        }
    }

    @Test fun deletingSourceCascades() {
        val src = db.addSource()
        import(src, playlist)
        runBlocking { db.sourceDao().delete(src.id) }
        assertThat(db.channelDao().count(src.id)).isEqualTo(0)
        assertThat(db.categoryDao().getAllBlocking(src.id)).isEmpty()
    }
}
