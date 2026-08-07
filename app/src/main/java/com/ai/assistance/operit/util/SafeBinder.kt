package com.ai.assistance.operit.util

import android.os.DeadObjectException
import android.os.IBinder

/**
 * Utilitar care protejează apelurile Binder împotriva țintelor moarte / înghețate (frozen apps).
 *
 * Context ANR:
 *   - logcat raporta: "pid ... sent binder code 2 ... to frozen apps and got error -74"
 *   - Aceasta apare când trimitem un apel Binder (ex: bindService / pingBinder / getContentProvider)
 *     către un proces țintă care a fost înghețat de Android (Freezable). Răspunsul poate întârzia
 *     (timeout) cât timp procesul țintă e înghețat și poate contribui la ANR pe main thread.
 *
 * Fix:
 *   - `isBinderAlive()`/`pingBinder()` înainte de a trimite apelul, ca să nu așteptăm un proxy mort.
 *   - Un wrapper safe care tratează DeadObjectException (echivalentul erorii -74) și întoarce null
 *     în loc să arunce pe main.
 */
object SafeBinder {

    @PublishedApi
    internal const val TAG = "SafeBinder"

    /**
     * Verifică dacă un binder proxy este încă conectat și răspunde (evită erorile -74).
     *
     * @return true dacă binderul poate fi folosit în siguranță; false dacă e mort/înghețat.
     */
    fun isAlive(binder: IBinder?): Boolean {
        if (binder == null) return false
        return try {
            binder.isBinderAlive && binder.pingBinder()
        } catch (e: DeadObjectException) {
            AppLogger.w(TAG, "Binder dead (DeadObjectException) - skip call", e)
            false
        } catch (e: Throwable) {
            AppLogger.w(TAG, "Binder liveness check failed - skip call", e)
            false
        }
    }

    /**
     * Execută un apel Binder în siguranță.
     *
     * @param binder proxy-ul Binder care trebuie interogat.
     * @param onFailure callback opțional apelat când ținta nu este valabilă sau apelul a eșuat.
     * @param block blocul ce primește binderul valid și execută apelul efectiv.
     * @return rezultatul lui [block], sau null dacă binderul nu este în viață sau blocul a aruncat.
     */
    inline fun <T> safeBinderCall(
        binder: IBinder?,
        crossinline onFailure: () -> Unit = {},
        block: (IBinder) -> T?
    ): T? {
        if (!isAlive(binder)) {
            AppLogger.w(TAG, "Binder not alive - dropped Binder call to avoid -74 / frozen app stall")
            onFailure()
            return null
        }
        val b = binder ?: return null
        return try {
            block(b)
        } catch (e: DeadObjectException) {
            AppLogger.w(TAG, "Binder call failed: DeadObjectException - error -74 equivalent", e)
            onFailure()
            null
        } catch (e: android.os.TransactionTooLargeException) {
            AppLogger.w(TAG, "Binder call failed: TransactionTooLargeException", e)
            onFailure()
            null
        } catch (e: RuntimeException) {
            if (e.cause is DeadObjectException) {
                AppLogger.w(TAG, "Binder call failed (wrapped DeadObjectException): error -74", e)
            } else {
                AppLogger.w(TAG, "Binder call failed with RuntimeException", e)
            }
            onFailure()
            null
        } catch (e: Throwable) {
            AppLogger.w(TAG, "Binder call failed with unexpected error", e)
            onFailure()
            null
        }
    }

    /**
     * Convenție pentru un proxy Binder care poate fi accesat printr-un getter opțional.
     */
    inline fun <T> safeBinderCall(
        binderProvider: () -> IBinder?,
        crossinline onFailure: () -> Unit = {},
        block: (IBinder) -> T?
    ): T? {
        val binder: IBinder? = try {
            binderProvider()
        } catch (e: Throwable) {
            AppLogger.w(TAG, "Failed to obtain Binder proxy", e)
            onFailure()
            null
        }
        return safeBinderCall(binder, onFailure, block)
    }
}
