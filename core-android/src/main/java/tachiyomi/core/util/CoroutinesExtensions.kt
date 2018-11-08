package tachiyomi.core.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

fun launchUI(block: suspend CoroutineScope.() -> Unit): Job {
  return GlobalScope.launch(Dispatchers.Main, CoroutineStart.DEFAULT, block)
}

fun launchNow(block: suspend CoroutineScope.() -> Unit): Job {
  return GlobalScope.launch(Dispatchers.Main, CoroutineStart.UNDISPATCHED, block)
}
