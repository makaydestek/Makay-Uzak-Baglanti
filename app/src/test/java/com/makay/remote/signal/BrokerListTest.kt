package com.makay.remote.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MqttSignalClient.buildBrokerList — masaustu `cloud-signal-bridge.js:BROKERS`
 * ile ayni varsayilan sirayi korur, kullanici broker'ini ONE alir.
 */
class BrokerListTest {

    @Test
    fun nullCustomFallsBackToWindowsBrokerOrder() {
        val list = MqttSignalClient.buildBrokerList(null)
        assertEquals(MqttSignalClient.DEFAULT_BROKERS, list)
        // Windows `cloud-signal-bridge.js:BROKERS` sirasi
        assertEquals(
            listOf(
                "wss://broker.emqx.io:8084/mqtt",
                "wss://broker.hivemq.com:8884/mqtt",
                "wss://test.mosquitto.org:8081/mqtt"
            ),
            list
        )
    }

    @Test
    fun emptyAndBlankCustomAreIgnored() {
        assertEquals(MqttSignalClient.DEFAULT_BROKERS, MqttSignalClient.buildBrokerList(""))
        assertEquals(MqttSignalClient.DEFAULT_BROKERS, MqttSignalClient.buildBrokerList("   "))
    }

    @Test
    fun customBrokerIsPrependedAndDefaultsRemainAsFallback() {
        val list = MqttSignalClient.buildBrokerList("wss://broker.ornek.com:8084/mqtt")
        assertEquals("wss://broker.ornek.com:8084/mqtt", list.first())
        assertEquals(MqttSignalClient.DEFAULT_BROKERS, list.drop(1))
        assertEquals(MqttSignalClient.DEFAULT_BROKERS.size + 1, list.size)
    }

    /** ws:// de kabul edilir (yerel test broker'lari). */
    @Test
    fun plainWsSchemeIsAccepted() {
        val list = MqttSignalClient.buildBrokerList("ws://192.168.1.10:9001")
        assertEquals("ws://192.168.1.10:9001", list.first())
        assertTrue(list.drop(1) == MqttSignalClient.DEFAULT_BROKERS)
    }

    /** Gecersiz sema yok sayilir — uygulama emniyetli bicimde halka aciga duser. */
    @Test
    fun invalidSchemeIsRejected() {
        for (bad in listOf(
            "http://broker.ornek.com:1883",
            "tcp://broker.ornek.com:1883",
            "broker.ornek.com",
            "broker.ornek.com:1883",
            "file:///etc/passwd",
            "javascript:alert(1)"
        )) {
            assertEquals("girdi: $bad", MqttSignalClient.DEFAULT_BROKERS, MqttSignalClient.buildBrokerList(bad))
        }
    }

    /** Sondaki egik çizgi temizlenir (ayni adres iki kez denenmesin). */
    @Test
    fun trailingSlashesAreTrimmed() {
        val list = MqttSignalClient.buildBrokerList("wss://broker.ornek.com:8084/mqtt///")
        assertEquals("wss://broker.ornek.com:8084/mqtt", list.first())
    }

    /** Kullanici zaten varsayilan bir adres girerse tekrar eklenmez. */
    @Test
    fun duplicateOfDefaultIsNotRepeated() {
        val list = MqttSignalClient.buildBrokerList("wss://broker.emqx.io:8084/mqtt")
        assertEquals(MqttSignalClient.DEFAULT_BROKERS, list)
    }

    /** Bosluklar temizlenir (yapistirma hatasi). */
    @Test
    fun surroundingWhitespaceIsTrimmed() {
        val list = MqttSignalClient.buildBrokerList("  wss://broker.ornek.com:8084/mqtt  ")
        assertEquals("wss://broker.ornek.com:8084/mqtt", list.first())
    }

    /** Varsayilan broker listesi Windows ile birebir ayni ve degerisiz. */
    @Test
    fun defaultBrokersAreUnique() {
        val b = MqttSignalClient.DEFAULT_BROKERS
        assertEquals(3, b.size)
        assertEquals(b.size, b.toSet().size)
        assertTrue(b.all { it.startsWith("wss://") })
    }
}
