package app.aaps.plugins.sync.nsShared

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.plugins.sync.R
import app.aaps.plugins.sync.databinding.DialogLicenseKeyBinding
import dagger.android.support.DaggerDialogFragment
import javax.inject.Inject

/**
 * Dialog for entering Remote Control premium license key.
 */
class LicenseKeyDialog : DaggerDialogFragment() {
    
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var licenseValidator: LicenseKeyValidator
    
    private var _binding: DialogLicenseKeyBinding? = null
    private val binding get() = _binding!!
    
    var onLicenseActivated: (() -> Unit)? = null
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogLicenseKeyBinding.inflate(inflater, container, false)
        isCancelable = false
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        binding.btnActivate.setOnClickListener {
            val licenseKey = binding.licenseKeyInput.text.toString()
            
            if (licenseKey.isEmpty()) {
                binding.errorText.visibility = View.VISIBLE
                binding.errorText.text = rh.gs(R.string.license_key_required)
                return@setOnClickListener
            }
            
            if (licenseValidator.validateLicenseKey(licenseKey)) {
                // Success
                aapsLogger.info(LTag.NSCLIENT, "[License] License activated successfully")
                dismiss()
                onLicenseActivated?.invoke()
            } else {
                // Failed
                binding.errorText.visibility = View.VISIBLE
                binding.errorText.text = rh.gs(R.string.license_key_invalid)
                binding.licenseKeyInput.text?.clear()
            }
        }
        
        binding.btnCancel.setOnClickListener {
            aapsLogger.info(LTag.NSCLIENT, "[License] License activation cancelled")
            dismiss()
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
