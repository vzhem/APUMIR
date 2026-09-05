package com.vladimir.messenger.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Код восстановления - основа опознания человека после переустановки.
 *
 * Главное свойство: тот же никнейм и тот же код обязаны давать ТОТ ЖЕ адрес,
 * иначе человек снова станет для собеседников незнакомцем. Обратное свойство
 * не менее важно: чужой никнейм без кода не должен давать чужой адрес.
 */
class RecoveryCodeTest {

    private val code = "ABCDE-FGHJK-MNPQR-STUVW"

    @Test
    fun `same nickname and code always give the same address`() {
        val first = RecoveryCode.deriveNodeId("anna", code)
        val second = RecoveryCode.deriveNodeId("anna", code)
        assertEquals(first, second)
        assertTrue(first != null)
    }

    @Test
    fun `address survives a reinstall on another phone`() {
        // Ровно тот случай, ради которого всё делается: человек ввёл свои
        // никнейм и код на чистой установке и получил прежний адрес.
        val before = RecoveryCode.deriveNodeId("anna", code)
        val afterReinstall = RecoveryCode.deriveNodeId("  @Anna  ", RecoveryCode.format(code.lowercase()))
        assertEquals(before, afterReinstall)
    }

    @Test
    fun `stolen nickname without the code gives a different address`() {
        // Никнеймы публичны. Знание чужого имени НЕ должно давать чужой адрес,
        // иначе вместе с ним достались бы чужой ранг и доверие.
        val owner = RecoveryCode.deriveNodeId("anna", code)
        val impostor = RecoveryCode.deriveNodeId("anna", "ZZZZZ-YYYYY-XXXXX-WWWWW")
        assertNotEquals(owner, impostor)
    }

    @Test
    fun `same code under different nicknames gives different addresses`() {
        val anna = RecoveryCode.deriveNodeId("anna", code)
        val stas = RecoveryCode.deriveNodeId("stas", code)
        assertNotEquals(anna, stas)
    }

    @Test
    fun `address has the exact shape the core expects`() {
        // Ядро принимает pk_ и ровно 32 шестнадцатеричных знака в нижнем регистре.
        val nodeId = RecoveryCode.deriveNodeId("anna", code)!!
        assertTrue(nodeId.matches(Regex("^pk_[0-9a-f]{32}$")))
    }

    @Test
    fun `private key differs from the address and is stable`() {
        val nodeId = RecoveryCode.deriveNodeId("anna", code)!!
        val privateKey = RecoveryCode.derivePrivateKey("anna", code)!!
        assertTrue(privateKey.startsWith("sk_"))
        assertNotEquals(nodeId.removePrefix("pk_"), privateKey.removePrefix("sk_"))
        assertEquals(privateKey, RecoveryCode.derivePrivateKey("anna", code))
    }

    @Test
    fun `typing the code in any style still works`() {
        val canonical = RecoveryCode.deriveNodeId("anna", code)
        assertEquals(canonical, RecoveryCode.deriveNodeId("anna", "abcdefghjkmnpqrstuvw"))
        assertEquals(canonical, RecoveryCode.deriveNodeId("anna", "ABCDE FGHJK MNPQR STUVW"))
        assertEquals(canonical, RecoveryCode.deriveNodeId("anna", "abcde-FGHJK-mnpqr-STUVW"))
    }

    @Test
    fun `incomplete or empty input is rejected`() {
        assertNull(RecoveryCode.deriveNodeId("anna", "ABCDE"))
        assertNull(RecoveryCode.deriveNodeId("anna", ""))
        assertNull(RecoveryCode.deriveNodeId("", code))
        assertNull(RecoveryCode.deriveNodeId("   ", code))
        assertFalse(RecoveryCode.isValid("ABCDE"))
        assertTrue(RecoveryCode.isValid(code))
    }

    @Test
    fun `generated codes are valid, readable and unique`() {
        val codes = (1..50).map { RecoveryCode.generate() }
        for (generated in codes) {
            assertTrue(RecoveryCode.isValid(generated))
            // Знаки, которые легко спутать, в код не попадают.
            assertFalse(generated.any { it in "01OIL" })
            assertEquals(4, generated.split("-").size)
        }
        assertEquals(codes.size, codes.toSet().size)
    }
}
