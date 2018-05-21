
import com.github.mbelling.ws281x.Color
import com.github.mbelling.ws281x.LedStrip
import com.github.mbelling.ws281x.LedStripType
import com.github.mbelling.ws281x.Ws281xLedStrip
import com.google.gson.JsonObject
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.ContentNegotiation
import io.ktor.gson.gson
import io.ktor.http.HttpStatusCode
import io.ktor.request.receive
import io.ktor.response.header
import io.ktor.response.respond
import io.ktor.routing.options
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.actor
import kotlinx.coroutines.experimental.channels.produce
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.runBlocking
import kotlinx.coroutines.experimental.selects.select

data class InputMessage(val red: Int, val green: Int, val blue: Int, val command: Int)

sealed class StripMsg
class Input(val data: InputMessage): StripMsg()

const val WAIT_MS = 50

fun colorWheel(pos: Int) = when {
    pos < 85 -> Color(pos * 3, 255 - pos * 3, 0)
    pos < 170 -> {
        val newPos = pos - 85
        Color(255 - newPos * 3, 0, newPos * 3)
    }
    else -> {
        val newPos = pos - 170
        Color(0, newPos * 3, 255 - pos * 3)
    }
}

fun colorWipe(r: Int, g: Int, b: Int, strip: LedStrip) {
    strip.setStrip(r, g, b)
    strip.render()
}


suspend fun setRainbow(strip: LedStrip) {
    (0 until 256).forEach { j ->
        (0 until strip.ledsCount).forEach { i ->
            strip.setPixel(i, colorWheel((i + j) and 255))
        }
        strip.render()
        delay(WAIT_MS)
    }
}

suspend fun setRainbowChase(strip: LedStrip) {
    (0 until 256).forEach { j ->
        (0 until strip.ledsCount).forEach { i ->
            strip.setPixel(i, colorWheel(((i * 256 / strip.ledsCount) + j) and 255))
        }
        strip.render()
        delay(WAIT_MS)
    }
}

fun clock() = produce {
    while (isActive) {
        delay(WAIT_MS)
        send(Unit)
    }
}

fun stripActor(clockChannel: ReceiveChannel<Unit>) = actor<StripMsg> {
    val strip = Ws281xLedStrip(60, 18, 800000, 10, 255, 0, false, LedStripType.WS2811_STRIP_GRB, true)
    var red = 0
    var green = 0
    var blue = 0
    var command = 0

    while (isActive) {
        select<Unit> {
            clockChannel.onReceive {
                when (command) {
                    1 -> setRainbow(strip)
                    2 -> setRainbowChase(strip)
                    else -> colorWipe(red, green, blue, strip)
                }
            }
            channel.onReceive {
                when (it) {
                    is Input -> {
                        println("Got $it")
                        val d = it.data
                        red = d.red
                        green = d.green
                        blue = d.blue
                        command = d.command
                    }
                }
            }
        }
    }
}

fun main(args: Array<String>) = runBlocking<Unit>{

    val strip = stripActor(clock())
    val server = embeddedServer(Netty, port = 42069) {
        install(ContentNegotiation) {
            gson {
            }
        }
        routing {
            post {
                val data = call.receive<InputMessage>()
                println(data)
                strip.send(Input(data))

                call.respond(HttpStatusCode.OK, JsonObject().apply {
                    addProperty("status", "OK")
                })
            }

            options {
                call.response.apply {
                    header("Access-Control-Allow-Origin", "*")
                    header("Access-Control-Allow-Methods", "GET,PUT,POST,DELETE")
                    header("Access-Control-Allow-Headers", "Content-Type")
                }
                call.respond(HttpStatusCode.OK)
            }
        }
    }
    server.start()
}