scala-loading-cache
=================

###Overview
Contains an implementation of EagerLoadingCache, a cache mechanism written in Scala inspired by Google Guava's LoadingCache in which values are loaded upon initialization and reloaded on a defined time interval. This library is designed following the single-writer principle in that a single thread handles writes to the atomic reference container. The key behind this mechanism is the Atomic*.lazySet method which provides significantly cheaper volatile writes. Load operations happen concurrently in a non-blocking manner using the global ExecutionContext.

###Tell Me More
The idea behind the single-writer principle is that since only one thread is responsible for setting volatile values,
we can do so in such a way that requires less effort than if we had to synchronize multiple threads submitting write operations. The compromise occurs in propagration time: lazySet is not re-order previous write operations but may be re-ordered with subsequent operations in that memory synchronization between threads may occur after the lazySet has occurred and not as a result of the operation. To delve a little deaper, a lazySet operation invokes a StoreStore barrier but no StoreLoad barrier meaning that we wait to flush to main memory until some other synchronization event occurs.  Pragmatically, this delay is measured in nanoseconds. For a caching mechanism where data is in an already stale-state to begin with that is a more than acceptable compromise.

###Usage

####Like a normal map:
```
val cache: LoadingCache[String, String] = new EagerLoadingCacheBuilder().load("k", () => { "v" }).build()
val cachedResult: Option[String] = cache.get("k")
```
####Automatically refresh a value based on an interval:
```
import scala.concurrent.duration.DurationInt

val cache: LoadingCache[String, String] = new EagerLoadingCacheBuilder().load("k", () => { "v" }, 5 seconds).build()
val cachedResult: Option[String] = cache.get("k")
```

###Contributing
Fork, make change, submit PR

###License
Apache 2.0
