
package ink.abb.pogo.scraper.util

import ink.abb.pogo.scraper.util.Log
import java.util.concurrent.TimeUnit

class Helper {

	companion object {

		fun getRandomNumber(minnum: Int, maxnum: Int): Int {
			return (minnum + (Math.random() * ((maxnum - minnum) + 1))).toInt()
		}

		fun sleepSecond(second: Int) {
			TimeUnit.SECONDS.sleep(second.toLong())
		}
		fun sleepMilli(millis: Long) {
			TimeUnit.MILLISECONDS.sleep(millis)
		}		
	}
}