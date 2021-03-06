/**
 * Pokemon Go Bot  Copyright (C) 2016  PokemonGoBot-authors (see authors.md for more information)
 * This program comes with ABSOLUTELY NO WARRANTY;
 * This is free software, and you are welcome to redistribute it under certain conditions.
 *
 * For more information, refer to the LICENSE file in this repositories root directory
 */

package ink.abb.pogo.scraper.tasks

import com.pokegoapi.api.map.fort.Pokestop
import com.pokegoapi.google.common.geometry.S2LatLng
import ink.abb.pogo.scraper.Bot
import ink.abb.pogo.scraper.Context
import ink.abb.pogo.scraper.Settings
import ink.abb.pogo.scraper.Task
import ink.abb.pogo.scraper.util.Log
import ink.abb.pogo.scraper.util.Helper
import ink.abb.pogo.scraper.util.directions.getRouteCoordinates
import ink.abb.pogo.scraper.util.map.canLoot
import kotlin.concurrent.thread


class Walk(val sortedPokestops: List<Pokestop>, val lootTimeouts: Map<String, Long>) : Task {
    override fun run(bot: Bot, ctx: Context, settings: Settings) {
        if (!ctx.walking.compareAndSet(false, true)) {
            return
        }

        if (ctx.server.coordinatesToGoTo.size > 0) {
            val coordinates = ctx.server.coordinatesToGoTo.first()
            ctx.server.coordinatesToGoTo.removeAt(0)
            Log.normal("Walking to ${coordinates.latRadians()}, ${coordinates.lngRadians()}")

            if (settings.shouldFollowStreets) {
                walkRoute(bot, ctx, settings, S2LatLng.fromDegrees(coordinates.latRadians(), coordinates.lngRadians()), settings.speed, true)
            } else {
                walk(bot, ctx, settings, S2LatLng.fromDegrees(coordinates.latRadians(), coordinates.lngRadians()), settings.speed, true)
            }
        } else {
            val nearestUnused: List<Pokestop> = sortedPokestops.filter {
                val canLoot = it.canLoot(ignoreDistance = true, lootTimeouts = lootTimeouts, api = ctx.api)
                if (settings.spawnRadius == -1) {
                    canLoot
                } else {
                    val distanceToStart = settings.startingLocation.getEarthDistance(S2LatLng.fromDegrees(it.latitude, it.longitude))
                    canLoot && distanceToStart < settings.spawnRadius
                }
            }

            if (nearestUnused.isNotEmpty()) {
                // Select random pokestop from the 5 nearest while taking the distance into account
                val chosenPokestop = selectRandom(nearestUnused.take(settings.randomNextPokestop), ctx)

                if (settings.guiPortSocket > 0) {
                    ctx.server.sendPokestop(chosenPokestop)
                }

                if (settings.shouldDisplayPokestopName)
                    Log.normal("Walking to pokestop \"${chosenPokestop.details.name}\"")

                if (settings.shouldFollowStreets) {
                    walkRoute(bot, ctx, settings, S2LatLng.fromDegrees(chosenPokestop.latitude, chosenPokestop.longitude), settings.speed, false)
                } else {
                    walk(bot, ctx, settings, S2LatLng.fromDegrees(chosenPokestop.latitude, chosenPokestop.longitude), settings.speed, false)
                }
            }
        }
    }

    fun walk(bot: Bot, ctx: Context, settings: Settings, end: S2LatLng, speed: Double, sendDone: Boolean) {
        val start = S2LatLng.fromDegrees(ctx.lat.get(), ctx.lng.get())
        val diff = end.sub(start)
        val distance = start.getEarthDistance(end)
        val timeout = 200L
        // prevent division by 0
        if (speed.equals(0)) {
            return
        }
        val timeRequired = distance / speed
        val stepsRequired = timeRequired / (timeout.toDouble() / 1000.toDouble())
        // prevent division by 0
        if (stepsRequired.equals(0)) {
            return
        }
        val deltaLat = diff.latDegrees() / stepsRequired
        val deltaLng = diff.lngDegrees() / stepsRequired

        Log.normal("Walking to ${end.toStringDegrees()} in $stepsRequired steps.")
        var remainingSteps = stepsRequired

        thread(true, false, null, "Walk", 1, block = {
            var threadRun = true

            while(threadRun) {

                // default delay per steps
                var randomTimeout = timeout + (Helper.getRandomNumber(0, timeout.toInt()).toLong() * 2)
                Helper.sleepMilli(randomTimeout)

                // 10% chance to do NOTHING.
                var dummy = Helper.getRandomNumber(0,100)
                if (dummy <= 5) {

                    val sleeptime = Helper.getRandomNumber(1,5)
                    Log.yellow("I'm doing nothing .. Replicate human behavior (sleep for $sleeptime seconds.)")

                    Helper.sleepSecond(sleeptime)
                }

                else {

                    ctx.lat.addAndGet(deltaLat)
                    ctx.lng.addAndGet(deltaLng)

                    if (settings.guiPortSocket > 0) {
                        ctx.server.setLocation(ctx.lat.get(), ctx.lng.get())
                    }

                    remainingSteps--
                    if (remainingSteps <= 0) {
                        Log.normal("Destination reached.")
                        ctx.walking.set(false)
                        threadRun = false

                        if (sendDone) {
                            if (settings.guiPortSocket > 0)
                                ctx.server.sendGotoDone()
                        }

                        // stop at the pokestop for random seconds
                        ctx.stopAtPoint.getAndSet(true)
                        val randomStopTimeout = Helper.getRandomNumber(30, 600)
                        Log.blue("We are stopping at this Pokestop for $randomStopTimeout seconds.")
                        Helper.sleepSecond(randomStopTimeout)

                        ctx.stopAtPoint.getAndSet(false)
                        Log.magenta("We are done stopping. Begin to search for our next pokestop!")

                    }
                }
            }

        }) // end of thread

    }

    fun walkRoute(bot: Bot, ctx: Context, settings: Settings, end: S2LatLng, speed: Double, sendDone: Boolean) {
        if (speed.equals(0)) {
            return
        }
        val timeout = 200L
        val coordinatesList = getRouteCoordinates(S2LatLng.fromDegrees(ctx.lat.get(), ctx.lng.get()), end)
        if (coordinatesList.size <= 0) {
            walk(bot, ctx, settings, end, speed, sendDone)
        } else {            

            thread(true, false, null, "WalkRoute", 1, block = {
                var threadRun = true

                while(threadRun) {

                    // default delay per steps
                    var randomTimeout = timeout + (Helper.getRandomNumber(0, timeout.toInt()).toLong() * 2)
                    Helper.sleepMilli(randomTimeout)

                    // 10% chance to do NOTHING.
                    var dummy = Helper.getRandomNumber(0,100)
                    if (dummy <= 5) {

                        val sleeptime = Helper.getRandomNumber(1,5)
                        Log.yellow("I'm doing nothing .. Replicate human behavior (sleep for $sleeptime seconds.)")

                        Helper.sleepSecond(sleeptime)
                    }

                    else {

                        val start = S2LatLng.fromDegrees(ctx.lat.get(), ctx.lng.get())
                        val step = coordinatesList.first()
                        coordinatesList.removeAt(0)
                        val diff = step.sub(start)
                        val distance = start.getEarthDistance(step)
                        val timeRequired = distance / speed
                        val stepsRequired = timeRequired / (timeout.toDouble() / 1000.toDouble())

                        if (stepsRequired.equals(0)) {
                            threadRun = false                            
                        }

                        val deltaLat = diff.latDegrees() / stepsRequired
                        val deltaLng = diff.lngDegrees() / stepsRequired
                        var remainingSteps = stepsRequired
                        while (remainingSteps > 0) {
                            ctx.lat.addAndGet(deltaLat)
                            ctx.lng.addAndGet(deltaLng)

                            if (settings.guiPortSocket > 0) {
                                ctx.server.setLocation(ctx.lat.get(), ctx.lng.get())
                            }

                            remainingSteps--
                            Thread.sleep(timeout)
                        }

                        if (coordinatesList.size <= 0) {
                            Log.normal("Destination reached.")
                            ctx.walking.set(false)
                            threadRun = false
                                                        
                            if (sendDone) {
                                if (settings.guiPortSocket > 0)
                                    ctx.server.sendGotoDone()
                            }
                           
                            ctx.stopAtPoint.getAndSet(true)

                            val timeStop = Helper.getRandomNumber(200,600)
                            Log.magenta("We are going to stop at this POINT for $timeStop seconds.")
                            Helper.sleepSecond(timeStop)

                            ctx.stopAtPoint.getAndSet(false)
                            Log.cyan("We are done stopping now!")                        
                        }
                    }
                }
            }) // end of thread
        }
    }

    fun walkAndComeBack(bot: Bot, ctx: Context, settings: Settings, end: S2LatLng, speed: Double, sendDone: Boolean) {
        val start = S2LatLng.fromDegrees(ctx.lat.get(), ctx.lng.get())
        val diff = end.sub(start)
        val distance = start.getEarthDistance(end)
        val timeout = 200L
        // prevent division by 0
        if (speed.equals(0)) {
            return
        }
        val timeRequired = distance / speed
        val stepsRequired = timeRequired / (timeout.toDouble() / 1000.toDouble())
        // prevent division by 0
        if (stepsRequired.equals(0)) {
            return
        }
        val deltaLat = diff.latDegrees() / stepsRequired
        val deltaLng = diff.lngDegrees() / stepsRequired
        val deltaLat2 = -deltaLat
        val deltaLng2 = -deltaLng

        Log.normal("Walking to ${end.toStringDegrees()} in $stepsRequired steps.")
        var remainingStepsGoing = stepsRequired
        var remainingStepsComing = stepsRequired        

        thread(true, false, null, "WalkAndComeBack", 1, block = {
            var threadRun = true

            while(threadRun) {

                // default delay per steps
                var randomTimeout = timeout + (Helper.getRandomNumber(0, timeout.toInt()).toLong() * 2)
                Helper.sleepMilli(randomTimeout)

                // 10% chance to do NOTHING.
                var dummy = Helper.getRandomNumber(0,100)
                if (dummy <= 5) {

                    val sleeptime = Helper.getRandomNumber(1,5)
                    Log.yellow("I'm doing nothing .. Replicate human behavior (sleep for $sleeptime seconds.)")

                    Helper.sleepSecond(sleeptime)
                }

                else {

                    if (remainingStepsGoing > 0) {
                        ctx.lat.addAndGet(deltaLat)
                        ctx.lng.addAndGet(deltaLng)

                        if (settings.guiPortSocket > 0) {
                            ctx.server.setLocation(ctx.lat.get(), ctx.lng.get())
                        }

                        remainingStepsGoing--
                    } else if (remainingStepsGoing <= 0) {
                        ctx.lat.addAndGet(deltaLat2)
                        ctx.lng.addAndGet(deltaLng2)

                        if (settings.guiPortSocket > 0) {
                            ctx.server.setLocation(ctx.lat.get(), ctx.lng.get())
                        }

                        remainingStepsComing--
                    }

                    if (remainingStepsComing <= 0) {
                        Log.normal("Destination reached.")
                        ctx.walking.set(false)
                        threadRun = false

                        if (sendDone) {
                            if (settings.guiPortSocket > 0)
                                ctx.server.sendGotoDone()
                        }
                    }
                }
            }        
        }) // end of thread
    }

    private fun selectRandom(pokestops: List<Pokestop>, ctx: Context): Pokestop {
        // Select random pokestop while taking the distance into account
        // E.g. pokestop is closer to the user -> higher probabilty to be chosen

        if (pokestops.size < 2)
            return pokestops.first()

        val currentPosition = S2LatLng.fromDegrees(ctx.lat.get(), ctx.lng.get())

        val distances = pokestops.map {
            val end = S2LatLng.fromDegrees(it.latitude, it.longitude)
            currentPosition.getEarthDistance(end)
        }
        val totalDistance = distances.sum()

        // Get random value between 0 and 1
        val random = Math.random()
        var cumulativeProbability = 0.0;

        for ((index, pokestop) in pokestops.withIndex()) {
            // Calculate probabilty proportional to the closeness
            val probability = (1 - distances[index] / totalDistance) / (pokestops.size - 1)

            cumulativeProbability += probability
            if (random <= cumulativeProbability) {
                return pokestop
            }
        }

        // should not happen
        return pokestops.first()
    }
}
