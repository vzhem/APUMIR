package com.vladimir.messenger.data.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * День рождения в роевом конверте (`bday`).
 *
 * Владелец 2026-10-15: «в день рождения у всех друзей появляется всплывающий
 * вверху пузырь... Если человек скрыл дату, то уведомление не появляется».
 * Значит, конверт обязан уметь две вещи: принести видимую дату и отозвать
 * ранее присланную (человек закрыл галочку).
 */
class BirthdayWireTest {

    @Test
    fun birthdayRoundTripKeepsDayAndMonth() {
        val envelope = GroupWire.buildBirthday("node-a", day = 12, month = 6, updatedAtMs = 1_700_000_000_000L)
        val packet = GroupWire.parse(envelope)
        assertTrue(packet is GroupWire.Packet.Birthday)
        val birthday = packet as GroupWire.Packet.Birthday
        assertEquals("node-a", birthday.ownerId)
        assertEquals(12, birthday.day)
        assertEquals(6, birthday.month)
        assertEquals(1_700_000_000_000L, birthday.updatedAtMs)
    }

    @Test
    fun revokeCarriesZeroesAndParses() {
        val packet = GroupWire.parse(GroupWire.buildBirthday("node-a", day = 0, month = 0, updatedAtMs = 5L))
        assertTrue(packet is GroupWire.Packet.Birthday)
        assertEquals(0, (packet as GroupWire.Packet.Birthday).day)
        assertEquals(0, packet.month)
    }

    /** Год рождения по сети не едет: в конверте его просто нет. */
    @Test
    fun envelopeHasNoYearField() {
        val envelope = GroupWire.buildBirthday("node-a", day = 29, month = 2, updatedAtMs = 7L)
        assertEquals("APUGRP1|bday|node-a|29|2|7", envelope)
    }

    @Test
    fun malformedBirthdayIsRejected() {
        val bad = listOf(
            "APUGRP1|bday||12|6|7", // нет узла
            "APUGRP1|bday|node-a|32|6|7", // дня 32 не бывает
            "APUGRP1|bday|node-a|12|13|7", // месяца 13 не бывает
            "APUGRP1|bday|node-a|31|4|7", // 31 апреля не бывает
            "APUGRP1|bday|node-a|12|6|-1", // отрицательная метка времени
            "APUGRP1|bday|node-a|12|6", // не хватает поля
            "APUGRP1|bday|node-a|12|6|7|0", // лишнее поле
            "APUGRP1|bday|node-a|день|6|7", // не число
        )
        for (envelope in bad) {
            assertNull("конверт: $envelope", GroupWire.parse(envelope))
        }
    }

    /** 29 февраля — существующая дата, её конверт обязан принимать. */
    @Test
    fun leapDayIsAccepted() {
        val packet = GroupWire.parse(GroupWire.buildBirthday("node-a", day = 29, month = 2, updatedAtMs = 9L))
        assertTrue(packet is GroupWire.Packet.Birthday)
    }

    /** Конверт дня рождения не должен разбираться как чужой вид. */
    @Test
    fun birthdayDoesNotLookLikeAnotherPacket() {
        val envelope = GroupWire.buildBirthday("node-a", day = 3, month = 11, updatedAtMs = 1L)
        val packet = GroupWire.parse(envelope)
        assertTrue(packet is GroupWire.Packet.Birthday)
        assertTrue(GroupWire.isGroupPacket(envelope))
    }
}
