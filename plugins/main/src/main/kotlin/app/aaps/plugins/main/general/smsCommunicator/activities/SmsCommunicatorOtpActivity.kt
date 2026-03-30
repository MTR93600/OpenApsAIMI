package app.aaps.plugins.main.general.smsCommunicator.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.Resources
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.smsCommunicator.SmsCommunicator
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.plugins.main.R
import app.aaps.plugins.main.databinding.SmscommunicatorActivityOtpBinding
import app.aaps.plugins.main.general.smsCommunicator.otp.OneTimePassword
import app.aaps.plugins.main.general.smsCommunicator.otp.OneTimePasswordValidationResult
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import net.glxn.qrgen.android.QRCode
import javax.inject.Inject

class SmsCommunicatorOtpActivity : TranslatedDaggerAppCompatActivity() {

    companion object {
        private const val TAG = "SmsOtpActivity"
        /** Avoid huge bitmaps / main-thread stalls on high-resolution displays (can look like black screen then exit). */
        private const val MAX_QR_EDGE_PX = 768
    }

    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var smsCommunicator: SmsCommunicator
    @Inject lateinit var otp: OneTimePassword
    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var aapsLogger: AAPSLogger

    private lateinit var binding: SmscommunicatorActivityOtpBinding
    private var otpTextWatcher: TextWatcher? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        binding = SmscommunicatorActivityOtpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        title = rh.gs(R.string.smscommunicator_tab_otp_label)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        otpTextWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable) {
                val checkResult = otp.checkOTP(s.toString())

                binding.otpVerifyLabel.text = when (checkResult) {
                    OneTimePasswordValidationResult.OK                 -> rh.gs(R.string.smscommunicator_otp_verification_ok)
                    OneTimePasswordValidationResult.ERROR_WRONG_LENGTH -> rh.gs(R.string.smscommunicator_otp_verification_ivalid_size)
                    OneTimePasswordValidationResult.ERROR_WRONG_PIN    -> rh.gs(R.string.smscommunicator_otp_verification_wrong_pin)
                    OneTimePasswordValidationResult.ERROR_WRONG_OTP    -> rh.gs(R.string.smscommunicator_otp_verification_wrong_otp)
                }

                binding.otpVerifyLabel.setTextColor(
                    when (checkResult) {
                        OneTimePasswordValidationResult.OK                 -> Color.GREEN
                        OneTimePasswordValidationResult.ERROR_WRONG_LENGTH -> Color.YELLOW
                        OneTimePasswordValidationResult.ERROR_WRONG_PIN    -> Color.RED
                        OneTimePasswordValidationResult.ERROR_WRONG_OTP    -> Color.RED
                    }
                )
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                /* left blank because we only need afterTextChanged */
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                /* left blank because we only need afterTextChanged */
            }
        }
        binding.otpVerifyEdit.addTextChangedListener(otpTextWatcher)

        binding.otpReset.setOnClickListener {
            OKDialog.showConfirmation(
                this,
                rh.gs(R.string.smscommunicator_otp_reset_title),
                rh.gs(R.string.smscommunicator_otp_reset_prompt),
                Runnable {
                    uel.log(Action.OTP_RESET, Sources.SMS)
                    otp.ensureKey(true)
                    updateGui()
                    ToastUtils.Long.infoToast(this, rh.gs(R.string.smscommunicator_otp_reset_successful))
                })
        }

        binding.otpProvisioning.setOnLongClickListener {
            OKDialog.showConfirmation(
                this,
                rh.gs(R.string.smscommunicator_otp_export_title),
                rh.gs(R.string.smscommunicator_otp_export_prompt),
                Runnable {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("OTP Secret", otp.provisioningSecret())
                    clipboard.setPrimaryClip(clip)
                    ToastUtils.Long.infoToast(this, rh.gs(R.string.smscommunicator_otp_export_successful))
                    uel.log(Action.OTP_EXPORT, Sources.SMS)
                })

            true
        }
    }

    @Synchronized
    override fun onResume() {
        super.onResume()
        updateGui()
    }

    override fun onDestroy() {
        super.onDestroy()
        otpTextWatcher?.let { binding.otpVerifyEdit.removeTextChangedListener(it) }
        binding.otpReset.setOnClickListener(null)
        binding.otpProvisioning.setOnLongClickListener(null)
    }

    private fun updateGui() {
        when (val keyStatus = otp.ensureProvisioningKeyLoaded()) {
            OneTimePassword.ProvisioningKeyStatus.FAILED -> {
                aapsLogger.error(LTag.SMS, "$TAG: crypto/key unavailable — cannot show QR")
                binding.otpProvisioning.setImageDrawable(null)
                binding.otpProvisioning.visibility = View.GONE
                ToastUtils.Long.errorToast(this, rh.gs(R.string.smscommunicator_otp_key_load_failed))
                return
            }
            OneTimePassword.ProvisioningKeyStatus.REGENERATED_SECRET ->
                ToastUtils.Long.infoToast(this, rh.gs(R.string.smscommunicator_otp_secret_auto_repaired))
            OneTimePassword.ProvisioningKeyStatus.READY -> Unit
        }

        try {
            val displayMetrics = Resources.getSystem().displayMetrics
            val width = displayMetrics.widthPixels
            val height = displayMetrics.heightPixels
            val shortEdge = minOf(width, height, MAX_QR_EDGE_PX)
            val dim = (shortEdge * 0.85).toInt().coerceAtLeast(1)
            val provURI = otp.provisioningURI()

            if (provURI.isNullOrBlank()) {
                aapsLogger.warn(LTag.SMS, "$TAG: provisioningURI blank after ensureProvisioningKeyLoaded (unexpected)")
                binding.otpProvisioning.visibility = View.GONE
                ToastUtils.Long.errorToast(this, rh.gs(R.string.smscommunicator_otp_uri_build_failed))
                return
            }

            aapsLogger.debug(LTag.SMS, "$TAG: building QR dim=$dim uriLength=${provURI.length}")

            try {
                val myBitmap = QRCode.from(provURI)
                    .withErrorCorrection(ErrorCorrectionLevel.H)
                    .withSize(dim, dim)
                    .bitmap()
                binding.otpProvisioning.setImageBitmap(myBitmap)
                binding.otpProvisioning.visibility = View.VISIBLE
            } catch (e: Exception) {
                aapsLogger.error(LTag.SMS, "$TAG: QR bitmap failed dim=$dim", e)
                fabricPrivacy.logException(e)
                binding.otpProvisioning.setImageDrawable(null)
                binding.otpProvisioning.visibility = View.GONE
                ToastUtils.Long.errorToast(this, rh.gs(R.string.smscommunicator_otp_qr_generation_failed))
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.SMS, "$TAG: updateGui failed before QR", e)
            fabricPrivacy.logException(e)
            binding.otpProvisioning.visibility = View.GONE
            ToastUtils.Long.errorToast(this, rh.gs(R.string.smscommunicator_otp_uri_build_failed))
        }
    }
}