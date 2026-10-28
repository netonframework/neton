package neton.core.event

import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SerializerDomainEventCodecTest {

    @Test
    fun sealedCodecRejectsRegistrationAndReturnsSnapshots() {
        val codec = SerializerDomainEventCodec()
        val snapshot = codec.registered()
        codec.register(ThingCreated::class, ThingCreated.serializer())
        assertEquals(emptySet(), snapshot)
        val bus = DomainEventBus(codec = codec)
        bus.seal()
        bus.seal()
        assertFailsWith<IllegalStateException> { codec.register(ThingRemoved::class, ThingRemoved.serializer()) }
        val event = ThingCreated(1, "still decodes")
        assertEquals(event, codec.decode(event.eventTypeKey(), codec.encode(event)!!))
    }

    @Serializable
    data class ThingCreated(val id: Long, val title: String) : DomainEvent

    @Serializable
    data class ThingRemoved(val id: Long) : DomainEvent

    @Test
    fun roundTripsRegisteredEvents() {
        val codec = SerializerDomainEventCodec()
        codec.register(ThingCreated::class, ThingCreated.serializer())
        val event = ThingCreated(1, "hello")
        val payload = codec.encode(event)!!
        assertEquals(event, codec.decode(event.eventTypeKey(), payload))
    }

    @Test
    fun unregisteredEventsAreNotEncoded() {
        val codec = SerializerDomainEventCodec()
        assertNull(codec.encode(ThingRemoved(1)))
        assertNull(codec.decode(ThingRemoved::class.qualifiedName!!, "{\"id\":1}"))
    }

    @Test
    fun conflictingRegistrationIsRejected() {
        val codec = SerializerDomainEventCodec()
        codec.register(ThingCreated::class, ThingCreated.serializer())
        codec.register(ThingCreated::class, ThingCreated.serializer())
        @Suppress("UNCHECKED_CAST")
        assertFailsWith<IllegalArgumentException> {
            codec.register(ThingCreated::class, ThingRemoved.serializer() as kotlinx.serialization.KSerializer<ThingCreated>)
        }
        val event = ThingCreated(7, "preserved")
        assertEquals(event, codec.decode(event.eventTypeKey(), codec.encode(event)!!))
    }
}
