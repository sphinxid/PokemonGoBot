/**
 * Pokemon Go Bot  Copyright (C) 2016  PokemonGoBot-authors (see authors.md for more information)
 * This program comes with ABSOLUTELY NO WARRANTY;
 * This is free software, and you are welcome to redistribute it under certain conditions.
 *
 * For more information, refer to the LICENSE file in this repositories root directory
 */

package ink.abb.pogo.scraper

import com.google.common.util.concurrent.AtomicDouble
import com.pokegoapi.api.PokemonGo
import com.pokegoapi.api.map.MapObjects
import com.pokegoapi.api.player.PlayerProfile
import ink.abb.pogo.scraper.gui.SocketServer
import ink.abb.pogo.scraper.gui.WebServer
import com.pokegoapi.api.pokemon.Pokemon
import ink.abb.pogo.scraper.tasks.*
import ink.abb.pogo.scraper.util.Log
import ink.abb.pogo.scraper.util.Helper
import ink.abb.pogo.scraper.util.inventory.size
import ink.abb.pogo.scraper.util.pokemon.getIv
import ink.abb.pogo.scraper.util.pokemon.getIvPercentage
import ink.abb.pogo.scraper.util.pokemon.getStatsFormatted
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import com.pokegoapi.exceptions.LoginFailedException

class Bot(val api: PokemonGo, val settings: Settings) {

    var ctx = Context(
            api,
            api.playerProfile,
            AtomicDouble(settings.startingLatitude),
            AtomicDouble(settings.startingLongitude),
            AtomicLong(api.playerProfile.stats.experience),
            Pair(AtomicInteger(0), AtomicInteger(0)),
            Pair(AtomicInteger(0), AtomicInteger(0)),
            mutableSetOf(),
            SocketServer()
    )

    @Synchronized
    fun start() {        

        Log.normal()
        Log.normal("Name: ${ctx.profile.username}")
        Log.normal("Team: ${ctx.profile.team}")
        Log.normal("Pokecoin: ${ctx.profile.currencies.get(PlayerProfile.Currency.POKECOIN)}")
        Log.normal("Stardust: ${ctx.profile.currencies.get(PlayerProfile.Currency.STARDUST)}")
        Log.normal("Level ${ctx.profile.stats.level}, Experience ${ctx.profile.stats.experience}")
        Log.normal("Pokebank ${ctx.api.inventories.pokebank.pokemons.size + ctx.api.inventories.hatchery.eggs.size}/${ctx.profile.pokemonStorage}")
        Log.normal("Inventory ${ctx.api.inventories.itemBag.size()}/${ctx.profile.itemStorage}")
        //Log.normal("Inventory bag ${ctx.api.bag}")

        val compareName = Comparator<Pokemon> { a, b ->
            a.pokemonId.name.compareTo(b.pokemonId.name)
        }
        val compareIv = Comparator<Pokemon> { a, b ->
            // compare b to a to get it descending
            if (settings.sortByIV) {
                b.getIv().compareTo(a.getIv())
            } else {
                b.cp.compareTo(a.cp)
            }
        }
        api.inventories.pokebank.pokemons.sortedWith(compareName.thenComparing(compareIv)).map {
            val IV = it.getIvPercentage()
            "Have ${it.pokemonId.name} (${it.nickname}) with ${it.cp} CP and IV $IV% \r\n ${it.getStatsFormatted()}"
        }.forEach { Log.normal(it) }

        val keepalive = GetMapRandomDirection()
        val drop = DropUselessItems()
        val profile = UpdateProfile()
        val catch = CatchOneNearbyPokemon()
        val release = ReleasePokemon()
        val hatchEggs = HatchEggs()
        val export = Export()

        if (settings.export.length > 0)
            task(export)

        Helper.sleepSecond(2)
        task(keepalive)
        Log.normal("Getting initial pokestops...")

        val sleepTimeout = 10L
        var reply: MapObjects?
        do {
            reply = api.map.mapObjects
            Log.normal("Got ${reply.pokestops.size} pokestops")
            if (reply == null || reply.pokestops.size == 0) {
                Log.red("Retrying in $sleepTimeout seconds...")
                Thread.sleep(sleepTimeout * 1000)
            }
        } while (reply == null || reply.pokestops.size == 0)

        Helper.sleepSecond(5)
        val process = ProcessPokestops(reply.pokestops)

        Log.setContext(ctx)

        if(settings.guiPort > 0){
            Log.normal("Running webserver on port ${settings.guiPort}")
            WebServer().start(settings.guiPort, settings.guiPortSocket)
            ctx.server.start(ctx, settings.guiPortSocket)
        }

        // BotLoop 1        
        Helper.sleepSecond(Helper.getRandomNumber(3,7))
        Log.normal("Starting BotLoop1...")
        thread(true, false, null, "BotLoop1", 1, block = {
            var threadRun = true

            while(threadRun) {

                // keepalive
                task(keepalive)

                // process
                task(process)

                Helper.sleepSecond(Helper.getRandomNumber(4,7))
            }
        })

        // BotLoop 2
        Helper.sleepSecond(Helper.getRandomNumber(3,7))
        Log.normal("Starting BotLoop2...")
        thread(true, false, null, "BotLoop2", 1, block = {
            var threadRun = true

            while(threadRun) {

                synctask(profile)                
                synctask(hatchEggs)

                // drop items
                if (settings.shouldDropItems) {
                    synctask(drop)
                }  

                if (settings.export.length > 0)
                    task(export)

                displayStatus()                    

                Helper.sleepSecond(Helper.getRandomNumber(120,300))
            }
        })

        // BotLoop 3
        Helper.sleepSecond(Helper.getRandomNumber(3,7))
        Log.normal("Starting BotLoop3...")
        thread(true, false, null, "BotLoop3", 1, block = {
            var threadRun = true

            while(threadRun) {
                // catch pokemon
                if (settings.shouldCatchPokemons) {
                    synctask(catch)
                }

                // transfer pokemon
                if (settings.shouldAutoTransfer) {                            
                    synctask(release)
                }                              

                Helper.sleepSecond(Helper.getRandomNumber(3,10))
            }

        })

    }


    @Suppress("UNUSED_VARIABLE")
    fun synctask(task: Task) {
        synchronized(ctx) {
            synchronized(settings) {

                try {
                    Helper.sleepMilli((Helper.getRandomNumber(1,3) * 100).toLong())
                    task.run(this, ctx, settings)

                }                
                catch (lfe: LoginFailedException) {

                    lfe.printStackTrace()

                    val (api2, settings2) = login()

                    synchronized(ctx) {
                        ctx.api = api2
                    }
                }  
                catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    @Suppress("UNUSED_VARIABLE")
    fun task(task: Task) {
        try {
            Helper.sleepMilli((Helper.getRandomNumber(1,3) * 100).toLong())
            task.run(this, ctx, settings)
        }         
        catch (lfe: LoginFailedException) {

            lfe.printStackTrace()

            val (api2, settings2) = login()

            synchronized(ctx) {
                ctx.api = api2
            }
        } 
        catch (e: Exception) {
            e.printStackTrace()
        }       
    }

    @Synchronized
    fun stop() {
        // do something

        Log.red("Stopping bot loops...")
        Log.red("All bot loops stopped.")
    }

    fun displayStatus() {
        Log.blue("status of ctx.releasing => {$ctx.releasing.get().toString()}")
        Log.blue("status of ctx.stopAtPoint => {$ctx.stopAtPoint.get().toString()}")
        Log.blue("status of ctx.walking => {$ctx.walking.get().toString()}")
    }
}