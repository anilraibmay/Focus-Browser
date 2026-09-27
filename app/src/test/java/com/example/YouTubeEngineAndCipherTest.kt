package com.example

import com.example.util.PlayerCipherDeobfuscator
import com.example.util.YouTubeDedicatedEngine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YouTubeEngineAndCipherTest {

    @Test
    fun testYouTubeUrlDetection() {
        assertTrue(YouTubeDedicatedEngine.isYouTubeUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(YouTubeDedicatedEngine.isYouTubeUrl("https://youtu.be/dQw4w9WgXcQ"))
        assertTrue(YouTubeDedicatedEngine.isYouTubeUrl("https://m.youtube.com/shorts/abcdef12345"))
        assertTrue(YouTubeDedicatedEngine.isYouTubeUrl("https://www.youtube.com/embed/dQw4w9WgXcQ"))
        assertFalse(YouTubeDedicatedEngine.isYouTubeUrl("https://www.google.com"))
        assertFalse(YouTubeDedicatedEngine.isYouTubeUrl("https://vimeo.com/123456"))
        assertFalse(YouTubeDedicatedEngine.isYouTubeUrl(null))
    }

    @Test
    fun testExtractVideoId() {
        assertEquals("dQw4w9WgXcQ", YouTubeDedicatedEngine.extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", YouTubeDedicatedEngine.extractVideoId("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", YouTubeDedicatedEngine.extractVideoId("https://www.youtube.com/embed/dQw4w9WgXcQ?autoplay=1"))
        assertEquals("abcdef12345", YouTubeDedicatedEngine.extractVideoId("https://www.youtube.com/shorts/abcdef12345?feature=share"))
        assertEquals("dQw4w9WgXcQ", YouTubeDedicatedEngine.extractVideoId("https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=10s"))
        assertNull(YouTubeDedicatedEngine.extractVideoId("https://example.com/notyoutube"))
    }

    @Test
    fun testCipherOperations_ReverseSpliceSwap() {
        // Testa a sequência de operações atômicas em uma assinatura simulada
        val originalSignature = "ABCDEFGHIJ"
        val ops = listOf(
            PlayerCipherDeobfuscator.CipherOp.Reverse, // "JIHGFEDCBA"
            PlayerCipherDeobfuscator.CipherOp.Splice(2), // "HGFEDCBA"
            PlayerCipherDeobfuscator.CipherOp.Swap(3) // troca índice 0 ('H') com índice 3 ('E') -> "EGFHDCBA"
        )

        val result = PlayerCipherDeobfuscator.applyCipherOperations(originalSignature, ops)
        assertEquals("EGFHDCBA", result)
    }

    @Test
    fun testParseSignatureOperations_RealWorldPattern() {
        // Simula trecho minificado extraído do base.js do YouTube
        val mockBaseJs = """
            var ab = {
                cd: function(a, b) { var c = a[0]; a[0] = a[b % a.length]; a[b % a.length] = c; },
                ef: function(a) { a.reverse(); },
                gh: function(a, b) { a.splice(0, b); }
            };
            var testSig = function(a) {
                a = a.split("");
                ab.ef(a);
                ab.gh(a, 2);
                ab.cd(a, 3);
                return a.join("");
            };
        """.trimIndent()

        val ops = PlayerCipherDeobfuscator.parseSignatureOperations(mockBaseJs)
        assertEquals(3, ops.size)
        assertTrue(ops[0] is PlayerCipherDeobfuscator.CipherOp.Reverse)
        assertTrue(ops[1] is PlayerCipherDeobfuscator.CipherOp.Splice && (ops[1] as PlayerCipherDeobfuscator.CipherOp.Splice).count == 2)
        assertTrue(ops[2] is PlayerCipherDeobfuscator.CipherOp.Swap && (ops[2] as PlayerCipherDeobfuscator.CipherOp.Swap).index == 3)

        // Aplica o mock nas operações extraídas
        val res = PlayerCipherDeobfuscator.applyCipherOperations("ABCDEFGHIJ", ops)
        assertEquals("EGFHDCBA", res)
    }

    @Test
    fun testCipherPersistenceAndOrphanCleanup() {
        val appContext = org.robolectric.RuntimeEnvironment.getApplication()
        PlayerCipherDeobfuscator.init(appContext)

        // Cria arquivo temporário órfão simulado com mais de 2 horas
        val cacheDir = appContext.cacheDir
        val orphanFile = java.io.File(cacheDir, "yt_test_orphan.tmp").apply {
            writeText("orphan test content")
            setLastModified(System.currentTimeMillis() - 3 * 3600 * 1000L) // 3 horas atrás
        }
        assertTrue("Arquivo temporário deve existir antes da limpeza", orphanFile.exists())

        PlayerCipherDeobfuscator.limparArquivosTemporariosOrfaos()
        assertFalse("Arquivo temporário antigo deve ter sido removido", orphanFile.exists())
    }
}
