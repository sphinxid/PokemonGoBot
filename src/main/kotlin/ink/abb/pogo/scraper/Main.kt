/**
 * Pokemon Go Bot  Copyright (C) 2016  PokemonGoBot-authors (see authors.md for more information)
 * This program comes with ABSOLUTELY NO WARRANTY;
 * This is free software, and you are welcome to redistribute it under certain conditions.
 *
 * For more information, refer to the LICENSE file in this repositories root directory
 */

package ink.abb.pogo.scraper

import com.pokegoapi.api.PokemonGo
import com.pokegoapi.auth.CredentialProvider
import com.pokegoapi.auth.GoogleAutoCredentialProvider
import com.pokegoapi.auth.GoogleUserCredentialProvider
import com.pokegoapi.auth.PtcCredentialProvider
import com.pokegoapi.exceptions.LoginFailedException
import com.pokegoapi.exceptions.RemoteServerException
import com.pokegoapi.util.SystemTimeImpl
import ink.abb.pogo.scraper.util.Log
import okhttp3.OkHttpClient
import java.io.FileInputStream
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

val time = SystemTimeImpl()

fun getAuth(settings: Settings, http: OkHttpClient): CredentialProvider {
    val credentials = settings.credentials
    val auth = if (credentials is GoogleCredentials) {
        if (credentials.token.isBlank()) {

            val provider = GoogleUserCredentialProvider(http, time)

            println("Please go to " + GoogleUserCredentialProvider.LOGIN_URL)
            println("Enter authorisation code:")

            val access = readLine()

            // we should be able to login with this token
            provider.login(access)
            println("Refresh token:" + provider.getRefreshToken())
            Log.normal("Setting Google refresh token in your config")
            credentials.token = provider.refreshToken
            settings.writeProperty("config.properties", "token", credentials.token)

            provider
        } else {
            GoogleUserCredentialProvider(http, credentials.token, time)
        }
    } else if(credentials is GoogleAutoCredentials) {
        GoogleAutoCredentialProvider(http, credentials.username, credentials.password, time)
    } else if(credentials is PtcCredentials) {
        try {
            PtcCredentialProvider(http, credentials.username, credentials.password, time)
        } catch (e: LoginFailedException) {
            throw e
        } catch (e: RemoteServerException) {
            throw e
        } catch (e: Exception) {
            // sometimes throws ArrayIndexOutOfBoundsException or other RTE's
            throw RemoteServerException(e)
        }
    } else {
        throw IllegalStateException("Unknown credentials: ${credentials.javaClass}")
    }

    return auth
}

fun main(args: Array<String>) {

    val (api, settings) = login()

    val bot = Bot(api, settings)
    Runtime.getRuntime().addShutdownHook(thread(start = false) { bot.stop() })

    bot.start()
}

fun login(): Pair<PokemonGo, Settings> {
    val builder = OkHttpClient.Builder()
    builder.connectTimeout(60, TimeUnit.SECONDS)
    builder.readTimeout(60, TimeUnit.SECONDS)
    builder.writeTimeout(60, TimeUnit.SECONDS)
    val http = builder.build()

    val properties = Properties()

    val input = FileInputStream("config.properties")
    input.use {
        properties.load(it)
    }
    input.close()

    val settings = SettingsParser(properties).createSettingsFromProperties()

    Log.normal("Logging in to game server...")

/*
    val retryCount = 3
    val errorTimeout = 1000L

    var retries = retryCount

    var auth: CredentialProvider? = null
    do {
        try {
            auth = getAuth(settings, http)
        } catch (e: LoginFailedException) {
            Log.red("Server refused your login credentials. Are they correct?")
            System.exit(1)
        } catch (e: RemoteServerException) {
            Log.red("Server returned unexpected error: ${e.message}")
            if (retries-- > 0) {
                Log.normal("Retrying...")
                Thread.sleep(errorTimeout)
            }
        }
    } while (auth == null && retries >= 0)

    retries = retryCount

    do {
        try {
            api = PokemonGo(auth, http, time)
        } catch (e: LoginFailedException) {
            Log.red("Server refused your login credentials. Are they correct?")
            System.exit(1)
        } catch (e: RemoteServerException) {
            Log.red("Server returned unexpected error")
            if (retries-- > 0) {
                Log.normal("Retrying...")
                Thread.sleep(errorTimeout)
            }
        }
    } while (api == null && retries >= 0)

    if (api == null) {
        Log.red("Failed to login. Stopping")
        System.exit(1)
    }
*/

    var (api, auth) = getPokemonGoApi(settings, http)

    Log.normal("Logged in successfully")

    print("Getting profile data from pogo server")
    while (api.playerProfile == null) {
        print(".")
        Thread.sleep(3000)
    }
    println(".")

    return Pair(api, settings)
}

fun getPokemonGoApi(settings: Settings, http: OkHttpClient): Pair<PokemonGo, CredentialProvider> {
    val retryLimit = 5
    val errorTimeout = 5000L
    var countDown = retryLimit

    var auth = try {
        getAuth(settings, http)
    } catch (e: Exception) {
        null
    }

    if (auth == null) {        
        do {
            try {
                Log.normal("Retrying...")
                Thread.sleep(errorTimeout)

                auth = getAuth(settings, http)
                countDown--            
            } catch (e: Exception) {
                e.printStackTrace()
            }

        } while (auth == null && countDown > 0)

        if (countDown <= 0) {
            Log.red("Unable to Login - EXIT")
            System.exit(1)
        }
    }

    //////////////////////////////

    if (auth != null) {
        countDown = retryLimit

        var api = try {
            PokemonGo(auth, http, time)
        } catch(e: Exception) {
            null
        }

        if (api == null) {
            countDown = retryLimit
            do {
                try {
                    Log.normal("Retrying...")
                    Thread.sleep(errorTimeout)

                    api = PokemonGo(auth, http, time)
                    countDown--
                } catch(e: Exception) {
                    e.printStackTrace()
                }                
            } while (api == null && countDown >0)
        }

        if (api != null) {
            return Pair(api, auth)
        }
    } 

    Log.red("Cannot get PokemonGo API Object")
    System.exit(1)
    throw Exception("Failed to login!")
}