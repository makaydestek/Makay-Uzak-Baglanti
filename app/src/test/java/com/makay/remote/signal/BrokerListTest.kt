package com.makay.remote.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MqttSignalClient.buildBrokerList — varsayilan liste MQTT-5 ile calisan
 * halka acik broker'lardir; kullanici broker'ini ONE alir.
 *
 * NOT: Windows `cloud-signal-bridge.js:BROKERS` (MQTT 3.1.1) listesinden
 * farklidir: `broker.hivemq.com` wss ucunda MQTT-5 CONNACK vermez (Paho v5
 * ile ~10 sn timeout), bu yuzden Android listesinde yer almaz.
 */
class BrokerListTest {

    @Test
    fun nullCustomFallsBackToMqtt5BrokerOrder() {
        val list = MqttSignalClient.buildBrokerList(null)
        assertEquals(MqttSignalClient.DEFAULT_BROKERS, list)
        assertEquals(
            listOf(
                "wss://broker.emqx.io:8084/mqtt",
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

    /** Varsayilan broker listesi MQTT-5 ile calisan, degerisiz adreslerden olusur. */
    @Test
    fun defaultBrokersAreUnique() {
        val b = MqttSignalClient.DEFAULT_BROKERS
        assertEquals(2, b.size)
        assertEquals(b.size, b.toSet().size)
        assertTrue(b.all { it.startsWith("wss://") })
    }
}
