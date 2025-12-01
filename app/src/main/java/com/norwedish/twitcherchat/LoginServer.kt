package com.norwedish.twitcherchat

import io.ktor.http.ContentType
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object LoginServer {
    private var server: NettyApplicationEngine? = null

    // Functie om de server te starten
    fun start(onTokenReceived: (String) -> Unit) {
        if (server != null) { stop() }

        CoroutineScope(Dispatchers.IO).launch {
            server = embeddedServer(Netty, port = 3000) {
                routing {
                    // Endpoint #1: Vangt het initiële verzoek van de browser op
                    get("/") {
                        // Stuur een HTML-pagina met JavaScript terug naar de browser.
                        call.respondText(getHtmlPage(), ContentType.Text.Html)
                    }

                    // Endpoint #2: Vangt het verzoek van ons JavaScript op
                    get("/token") {
                        val token = call.request.queryParameters["access_token"]

                        if (!token.isNullOrEmpty()) {

                            // Stuur een succesbericht terug naar de pagina
                            call.respondText("Login succesvol! U kunt dit venster sluiten.")

                            // Geef het token door aan de UI
                            onTokenReceived(token)

                            // Stop de server, we hebben hem niet meer nodig
                            // Een kleine vertraging geeft de browser tijd om de response te tonen.
                            launch {
                                kotlinx.coroutines.delay(500)
                                stop()
                            }
                        } else {
                            call.respondText("Fout: Geen token gevonden.")
                        }
                    }
                }
            }.start(wait = true)
        }
    }

    // Functie om de server te stoppen
    fun stop() {
        server?.stop(500, 1000) // Kortere wachttijd
        server = null
    }

    // Helper functie die de HTML-pagina met JavaScript genereert
    private fun getHtmlPage(): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Authenticating...</title>
                <script>
                    // Wacht tot de pagina is geladen
                    window.onload = function() {
                        // Pak het fragment deel van de URL (alles na #)
                        const fragment = window.location.hash.substring(1);
                        if (fragment) {
                            // Zet het fragment om in URLSearchParams voor makkelijk uitlezen
                            const params = new URLSearchParams(fragment);
                            const accessToken = params.get('access_token');
                            
                            if (accessToken) {
                                // Stuur het token naar onze server op het /token endpoint
                                fetch('/token?access_token=' + accessToken);
                                document.body.innerText = 'Login succesvol! U kunt dit venster sluiten.';
                            } else {
                                document.body.innerText = 'Fout: Geen access token gevonden in URL.';
                            }
                        } else {
                            document.body.innerText = 'Fout: Geen login data ontvangen.';
                        }
                    };
                </script>
            </head>
            <body>
                Bezig met verwerken...
            </body>
            </html>
        """.trimIndent()
    }
}
