package io.nekohasekai.sagernet.kombain

import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.dp2px

/**
 * «Вход по отпечатку»: DAK opens only after the fingerprint / face / the phone's PIN. Locks again when it has
 * been in the background for a minute. The VPN itself keeps working and can be switched from the quick tile.
 */
object AppLock {

    private const val RELOCK_MS = 60_000L
    private const val AUTH = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    private var unlockedAt = 0L
    private var hiddenAt = 0L
    private var cover: View? = null
    private var prompting = false

    /** Can the phone confirm the owner at all (a fingerprint, face or at least a screen lock is set)? */
    fun available(activity: FragmentActivity) =
        BiometricManager.from(activity).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS

    fun onStop() { hiddenAt = SystemClock.elapsedRealtime() }

    /** Call from onStart / onCreate: covers the screen and asks to unlock when needed. */
    fun check(activity: FragmentActivity) {
        if (!DataStore.kbAppLock) { remove(); return }
        if (Build.VERSION.SDK_INT >= 33) activity.setRecentsScreenshotEnabled(false)
        val now = SystemClock.elapsedRealtime()
        val fresh = unlockedAt > 0 && (hiddenAt <= unlockedAt || now - hiddenAt < RELOCK_MS)
        if (fresh) { remove(); return }
        unlockedAt = 0
        show(activity)
        prompt(activity)
    }

    /** One confirmation, e.g. before switching the lock on or off. */
    fun confirm(activity: FragmentActivity, title: String, done: (Boolean) -> Unit) {
        if (!available(activity)) { done(false); return }
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                android.util.Log.i("DAK-Lock", "confirm ok")
                unlockedAt = SystemClock.elapsedRealtime(); done(true)
            }
            override fun onAuthenticationError(code: Int, msg: CharSequence) {
                android.util.Log.i("DAK-Lock", "confirm error $code $msg")
                done(false)
            }
        }).authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(AUTH).build())
    }

    private fun prompt(activity: FragmentActivity) {
        if (prompting || activity.isFinishing) return
        if (!available(activity)) {
            // the screen lock was removed from the phone: nothing to check against — don't lock the owner out
            unlockedAt = SystemClock.elapsedRealtime(); remove(); return
        }
        prompting = true
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                prompting = false
                unlockedAt = SystemClock.elapsedRealtime()
                remove()
            }
            override fun onAuthenticationError(code: Int, msg: CharSequence) {
                android.util.Log.i("DAK-Lock", "unlock error $code $msg"); prompting = false
            }
        }).authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle("Вход в DAK")
            .setSubtitle("Подтвердите, что это вы")
            .setAllowedAuthenticators(AUTH)
            .build())
    }

    private fun remove() {
        cover?.let { (it.parent as? ViewGroup)?.removeView(it) }
        cover = null
    }

    private fun show(activity: FragmentActivity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (cover?.parent === root) return
        remove()
        val ctx = activity
        cover = FrameLayout(ctx).apply {
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.kombain_bg))
            isClickable = true
            isFocusable = true
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(ImageView(ctx).apply { setImageResource(R.mipmap.ic_launcher) },
                    LinearLayout.LayoutParams(dp2px(96), dp2px(96)))
                addView(TextView(ctx).apply {
                    text = "DAK заблокирован"
                    textSize = 22f
                    setTextColor(ContextCompat.getColor(ctx, R.color.kombain_text))
                    setPadding(0, dp2px(18), 0, dp2px(6))
                })
                addView(TextView(ctx).apply {
                    text = "VPN продолжает работать"
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(ctx, R.color.kombain_text2))
                    setPadding(0, 0, 0, dp2px(22))
                })
                addView(MaterialButton(ctx).apply {
                    text = "Разблокировать"
                    setOnClickListener { prompt(activity) }
                })
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        root.addView(cover, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
}
