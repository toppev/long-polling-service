package dev.toppe.longpolling

import io.ktor.application.Application
import io.ktor.application.ApplicationCall
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.CORS
import io.ktor.features.CallLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.locations.*
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.response.respondBytes
import io.ktor.routing.routing
import io.ktor.util.KtorExperimentalAPI
import kotlinx.coroutines.delay
import org.slf4j.event.Level
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit.SECONDS

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

@KtorExperimentalLocationsAPI
@Location("/{id}")
class Subscription(val id: String) {
    @Location("/send")
    class Send(val parent: Subscription)
}

@KtorExperimentalAPI
@KtorExperimentalLocationsAPI
fun Application.module() {
    install(CallLogging) {
        level = Level.INFO
    }
    install(CORS) {
        method(HttpMethod.Options)
        method(HttpMethod.Delete)
        method(HttpMethod.Post)
        anyHost()
    }
    install(Locations)

    // Receivers waiting for senders
    val receivers = ConcurrentHashMap<String, ApplicationCall>()
    // Senders waiting for receivers
    val senders = ConcurrentHashMap<String, ApplicationCall>()

    routing {

        val configSecondsInMillis = { it: String ->
            SECONDS.toMillis(application.environment.config.property(it).getString().toLong())
        }
        val receiverTimeout = configSecondsInMillis("settings.timeout")
        val senderTimeout = configSecondsInMillis("settings.senderTimeout")

        val sendKey = application.environment.config.propertyOrNull("settings.sendKey")?.getString()

        // Subscribe
        post<Subscription> {
            // Check if the sender is waiting
            val sender = senders[it.id]
            environment.log.info("Subscription added. Sender waiting: ${sender != null}, (total waiting ${receivers.size} (R), ${senders.size} (S))")
            if (sender != null) {
                call.respondBytes(sender.receive(), ContentType.parse("application/json"))
                senders.remove(it.id)
                sender.respond(HttpStatusCode.OK)
            } else {
                // Otherwise wait for a sender
                receivers[it.id] = call
                delay(receiverTimeout)
                // Remove if still exists
                if (receivers[it.id] === call) {
                    receivers.remove(it.id)
                    call.respond(HttpStatusCode.RequestTimeout)
                }
            }
        }

        // Send to the subscriber of the id
        post<Subscription.Send> {
            // Header must match the authentication key if it exists in the application.conf
            if (sendKey != call.request.headers["Polling-Authentication"]) {
                call.respond(HttpStatusCode.Unauthorized)
            } else {
                val id = it.parent.id
                val receiver = receivers[id]
                environment.log.info("Sending to subscriber. Receiver waiting: ${receiver != null}, (total waiting ${receivers.size} (R), ${senders.size} (S))")
                if (receiver != null) {
                    receiver.respondBytes(call.receive(), ContentType.parse("application/json"))
                    receivers.remove(id)
                    call.respond(HttpStatusCode.OK)
                } else {
                    // Not found, wait if the receiver connects
                    senders[id] = call
                    delay(senderTimeout)
                    if (senders[id] === call) {
                        senders.remove(id)
                    }
                    call.respond(HttpStatusCode.RequestTimeout)
                }
            }
        }

        // Unsubscribe
        delete<Subscription> {
            receivers.remove(it.id)
            call.respond(HttpStatusCode.NoContent)
            environment.log.info("Deleted subscription, (total waiting ${receivers.size} (R), ${senders.size} (S))")
        }
    }
}