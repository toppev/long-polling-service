package dev.toppe.datapolling

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
import io.ktor.request.path
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
    install(Locations)
    install(CORS) {
        method(HttpMethod.Delete)
        anyHost() // Doesn't really matter
    }
    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().startsWith("/") }
    }

    val connections = ConcurrentHashMap<String, ApplicationCall>()

    routing {

        val timeout = SECONDS.toMillis(application.environment.config.property("settings.timeout").getString().toLong())
        val sendKey = application.environment.config.propertyOrNull("settings.sendKey")?.getString()

        // Subscribe
        post<Subscription> {
            connections[it.id] = call
            delay(timeout)
            // Remove if still exists
            if (connections.containsKey(it.id)) {
                connections.remove(it.id)
                call.respond(HttpStatusCode.RequestTimeout)
            }
        }

        // Send to the subscriber of the id
        post<Subscription.Send> {
            // Header must match the authentication key if it exists in the application.conf
            if (sendKey != call.request.headers["Polling-Authentication"]) {
                call.respond(HttpStatusCode.Unauthorized)
            } else {
                val receiver = connections[it.parent.id]
                if (receiver != null) {
                    receiver.respondBytes(call.receive(), ContentType.parse("application/json"))
                    connections.remove(it.parent.id)
                    call.respond(HttpStatusCode.OK)
                } else call.respond(HttpStatusCode.NotFound)
            }
        }

        // Unsubscribe
        delete<Subscription> {
            connections.remove(it.id)
            call.respond(HttpStatusCode.NoContent)
        }
    }

}