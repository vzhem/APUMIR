package com.vladimir.messenger.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сундук личности. От него зависит, узнают ли человека после переустановки,
 * поэтому проверяем и то, что он открывается своим, и то, что не открывается
 * чужим.
 */
class IdentityVaultTest {

    private val identity = IdentityVault.Identity(
        nodeId = "pk_52b8c6e5abce214b9347a6acc90588f2",
        privateKey = "sk_be8d4bcb17fb602585fab6d502584b80",
        displayName = "Анна",
        nickname = "anna",
    )

    private val password = "verygoodpassword"

    @Test
    fun `identity survives a reinstall`() {
        // Главный сценарий: переустановил, ввёл никнейм и пароль - вернулся
        // ТОТ ЖЕ адрес, значит для собеседников человек не менялся.
        val sealed = IdentityVault.seal(identity, "anna", password)!!
        val restored = IdentityVault.open(sealed, "anna", password)
        assertEquals(identity, restored)
    }

    @Test
    fun `nickname case and at sign do not matter`() {
        val sealed = IdentityVault.seal(identity, "@Anna", password)!!
        assertEquals(identity, IdentityVault.open(sealed, "anna", password))
        assertEquals(identity, IdentityVault.open(sealed, "  @ANNA ", password))
    }

    @Test
    fun `wrong password does not open the vault`() {
        val sealed = IdentityVault.seal(identity, "anna", password)!!
        assertNull(IdentityVault.open(sealed, "anna", "wrongpassword"))
        assertNull(IdentityVault.open(sealed, "anna", ""))
    }

    @Test
    fun `wrong nickname does not open the vault`() {
        // Пароль верный, но ник чужой - ключ выводится другой.
        val sealed = IdentityVault.seal(identity, "anna", password)!!
        assertNull(IdentityVault.open(sealed, "stas", password))
    }

    @Test
    fun `tampered content is rejected`() {
        val sealed = IdentityVault.seal(identity, "anna", password)!!
        val damaged = sealed.copyOf()
        damaged[damaged.size - 1] = (damaged.last() + 1).toByte()
        assertNull(IdentityVault.open(damaged, "anna", password))
    }

    @Test
    fun `truncated content is rejected`() {
        val sealed = IdentityVault.seal(identity, "anna", password)!!
        assertNull(IdentityVault.open(sealed.copyOf(10), "anna", password))
        assertNull(IdentityVault.open(ByteArray(0), "anna", password))
    }

    @Test
    fun `sealed bytes leak neither the key nor the name`() {
        val sealed = IdentityVault.seal(identity, "anna", password)!!
        val asText = sealed.toString(Charsets.ISO_8859_1)
        assertTrue(!asText.contains(identity.nodeId))
        assertTrue(!asText.contains(identity.privateKey))
        assertTrue(!asText.contains("Анна"))
    }

    @Test
    fun `sealing twice gives different bytes for the same identity`() {
        // Случайные соль и вектор: одинаковые сундуки выдавали бы, что пароль
        // не менялся, и позволяли бы сравнивать полки между собой.
        val first = IdentityVault.seal(identity, "anna", password)!!
        val second = IdentityVault.seal(identity, "anna", password)!!
        assertNotEquals(first.toList(), second.toList())
        assertEquals(identity, IdentityVault.open(second, "anna", password))
    }

    @Test
    fun `changing the password keeps the same identity`() {
        // Ради этого и сделан сундук: пароль меняется, человек остаётся прежним.
        val sealed = IdentityVault.seal(identity, "anna", password)!!
        val opened = IdentityVault.open(sealed, "anna", password)!!
        val resealed = IdentityVault.seal(opened, "anna", "brandnewpassword")!!
        val afterChange = IdentityVault.open(resealed, "anna", "brandnewpassword")!!
        assertEquals(identity.nodeId, afterChange.nodeId)
        assertNull(IdentityVault.open(resealed, "anna", password))
    }

    @Test
    fun `short password is refused`() {
        assertNull(IdentityVault.seal(identity, "anna", "short"))
        assertEquals(8, IdentityVault.MIN_PASSWORD_LENGTH)
    }

    @Test
    fun `empty nickname is refused`() {
        assertNull(IdentityVault.seal(identity, "   ", password))
        assertNull(IdentityVault.seal(identity, "@", password))
        assertNull(IdentityVault.shelfFor(""))
    }

    @Test
    fun `shelf address is stable and hides the nickname`() {
        val shelf = IdentityVault.shelfFor("anna")!!
        assertEquals(shelf, IdentityVault.shelfFor("@ANNA"))
        assertNotEquals(shelf, IdentityVault.shelfFor("stas"))
        assertTrue(shelf.matches(Regex("^[0-9a-f]{64}$")))
        assertTrue(!shelf.contains("anna"))
    }
}
